package com.careerorbit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.careerorbit.ai.*;
import com.careerorbit.entity.*;
import com.careerorbit.mapper.*;
import com.careerorbit.security.CurrentUser;
import com.careerorbit.storage.ObjectStorageService;
import com.careerorbit.common.BusinessException;
import com.fasterxml.jackson.databind.*;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.Comparator;

/**
 * 简历服务：上传解析、诊断、版本与采纳管理。
 * 上传走 MinIO + Tika + 大模型；诊断/采纳结果全部持久化，支持版本回溯与前后对比。
 *
 * <p>事务策略：涉及大模型调用的方法（{@link #upload}、{@link #diagnose}）**不使用**方法级
 * {@code @Transactional}，而是用 {@link TransactionTemplate} 只把“落库”那几步包进事务。
 * 原因是模型调用可能耗时数十秒，若整个方法都在事务里，会长时间占用数据库连接，并发时易耗尽连接池。</p>
 */
@Service
public class ResumeService {

    /** 日志。注意：绝不记录简历原文。 */
    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    /** 简历表 Mapper。 */
    private final ResumeMapper resumes;

    /** 诊断表 Mapper。 */
    private final ResumeDiagnosisMapper diagnoses;

    /** 当前登录用户。 */
    private final CurrentUser current;

    /** 对象存储（存放简历原文件）。 */
    private final ObjectStorageService storage;

    /** 聊天网关（抽取与诊断）。 */
    private final ChatGateway ai;

    /** JSON 工具。 */
    private final ObjectMapper json;

    /** 事务模板：只把落库步骤包进事务，模型调用留在事务外。 */
    private final TransactionTemplate tx;

    /** Tika 解析器（PDF/Word 抽文本）。 */
    private final Tika tika = new Tika();

    public ResumeService(ResumeMapper resumes, ResumeDiagnosisMapper diagnoses, CurrentUser current, ObjectStorageService storage, ChatGateway ai, ObjectMapper json, TransactionTemplate tx) {
        this.resumes = resumes;
        this.diagnoses = diagnoses;
        this.current = current;
        this.storage = storage;
        this.ai = ai;
        this.json = json;
        this.tx = tx;
    }

    /**
     * 上传简历：MinIO 存原文件 -> Tika 抽文本 -> 模型抽取结构化 JSON -> 落库。
     * 模型调用在事务外；仅“插入记录 + 回填下载地址”两步在一个事务内完成。
     * 任一步失败会删除已上传的原文件，避免存储残留。
     *
     * @param file 简历文件（PDF/DOC/DOCX）
     * @return 已保存的简历实体
     */
    public ResumeEntity upload(MultipartFile file) {
        log.info("上传简历: fileName={}, size={}", file == null ? null : file.getOriginalFilename(), file == null ? 0 : file.getSize());
        validate(file);
        ObjectStorageService.StoredObject stored = null;  // 失败时用于回滚对象存储
        try {
            final long userId = current.require().id;     // 事务外取一次用户 id
            stored = storage.put(file, "resumes/" + userId);
            final String objectKey = stored.key();
            String text = tika.parseToString(file.getInputStream());
            if (text.isBlank()) throw new BusinessException("未提取到文字，请上传非扫描版 PDF 或 Word");
            log.info("简历文本提取完成: userId={}, textLength={}", userId, text.length());
            JsonNode parsed = ai.json(PromptTemplates.resumeExtract(text), JsonNode.class);   // 模型调用（事务外）
            final String parsedStr = json.writeValueAsString(parsed);
            final String fileName = file.getOriginalFilename();

            // 仅把落库的两步包进事务
            ResumeEntity saved = tx.execute(status -> {
                var e = new ResumeEntity();
                e.userId = userId;
                e.fileName = fileName;
                e.objectKey = objectKey;
                e.fileUrl = "/api/resumes/pending/download";   // 先占位，拿到 id 后再改
                e.rawText = text;
                e.parsedJson = parsedStr;
                e.versionNo = 1;
                e.createdAt = LocalDateTime.now();
                resumes.insert(e);
                e.fileUrl = "/api/resumes/" + e.id + "/download";   // 回填正确下载地址
                resumes.updateById(e);
                return e;
            });
            log.info("简历上传成功: resumeId={}, userId={}", saved.id, userId);
            return saved;
        } catch (Exception e) {
            if (stored != null) try { storage.delete(stored.key()); } catch (Exception ignored) { }
            log.error("简历上传失败: fileName={}", file == null ? null : file.getOriginalFilename(), e);
            if (e instanceof BusinessException b) throw b;
            throw new BusinessException("简历处理失败：" + e.getMessage());
        }
    }

    /** 当前用户的全部简历版本（倒序）。 */
    public List<ResumeEntity> list() {
        return resumes.selectList(new QueryWrapper<ResumeEntity>().eq("user_id", current.require().id).orderByDesc("created_at"));
    }

    /**
     * 取一份简历并做归属校验（只能访问自己的）。
     *
     * @param id 简历 id
     * @return 简历实体
     */
    public ResumeEntity get(long id) {
        var e = resumes.selectById(id);
        if (e == null || !e.userId.equals(current.require().id)) {
            log.warn("简历不存在或无权限: resumeId={}", id);
            throw new BusinessException("简历不存在");
        }
        return e;
    }

    /**
     * 调用模型对已解析的简历做诊断，结果落 resume_diagnosis。
     * 模型调用在事务外，仅插入诊断记录这一步在事务内。
     *
     * @param id 简历 id
     * @return 诊断记录
     */
    public ResumeDiagnosisEntity diagnose(long id) {
        log.info("简历诊断: resumeId={}", id);
        var r = get(id);
        try {
            JsonNode result = ai.json(PromptTemplates.resumeDiagnosis(r.parsedJson), JsonNode.class);   // 模型调用（事务外）
            final String resultStr = json.writeValueAsString(result);
            ResumeDiagnosisEntity saved = tx.execute(status -> {
                var d = new ResumeDiagnosisEntity();
                d.resumeId = id;
                d.resultJson = resultStr;
                d.acceptedItemsJson = "[]";                  // 初始无已采纳项
                d.createdAt = LocalDateTime.now();
                diagnoses.insert(d);
                return d;
            });
            log.info("简历诊断完成: resumeId={}, diagnosisId={}", id, saved.id);
            return saved;
        } catch (Exception e) {
            log.error("简历诊断失败: resumeId={}", id, e);
            if (e instanceof BusinessException b) throw b;
            throw new BusinessException("诊断结果保存失败");
        }
    }

    /**
     * 取简历的最新诊断；若自身没有，沿父子版本链向上找根版本的诊断。
     *
     * @param resumeId 简历 id
     * @return 诊断记录或 null
     */
    public ResumeDiagnosisEntity diagnosis(long resumeId) {
        var resume = get(resumeId);
        while (resume != null) {
            var found = diagnoses.selectOne(new QueryWrapper<ResumeDiagnosisEntity>()
                    .eq("resume_id", resume.id).orderByDesc("created_at").last("LIMIT 1"));
            if (found != null) return found;
            resume = resume.parentId == null ? null : get(resume.parentId);   // 沿版本链上溯
        }
        return null;
    }

    /**
     * 用给定的结构化 JSON 保存一个新版本（不改原文件，仅新记录 + parentId 指向旧版）。
     *
     * @param id     父版本 id
     * @param parsed 新的结构化 JSON
     * @return 新版本实体
     */
    @Transactional
    public ResumeEntity saveVersion(long id, JsonNode parsed) {
        log.info("保存简历新版本: parentId={}", id);
        var parent = get(id);
        var next = new ResumeEntity();
        next.userId = parent.userId;
        next.fileName = parent.fileName;
        next.objectKey = parent.objectKey;
        next.fileUrl = parent.fileUrl;
        next.rawText = parent.rawText;
        next.parsedJson = parsed.toString();
        next.parentId = parent.id;
        next.versionNo = parent.versionNo + 1;
        next.createdAt = LocalDateTime.now();
        resumes.insert(next);
        log.info("简历新版本已保存: newId={}, versionNo={}", next.id, next.versionNo);
        return next;
    }

    /**
     * 采纳一条建议：生成新版本并记录来源，同时把该建议标记为已采纳（不能重复采纳）。
     *
     * @param diagnosisId  诊断记录 id
     * @param suggestionId 建议 id
     * @param parsed       采纳后的结构化 JSON
     * @return 新版本 + 更新后的诊断
     */
    @Transactional
    public Acceptance accept(long diagnosisId, String suggestionId, JsonNode parsed) {
        log.info("采纳建议: diagnosisId={}, suggestionId={}", diagnosisId, suggestionId);
        try {
            var d = requireDiagnosis(diagnosisId);
            var parent = get(d.resumeId);
            List<String> accepted = json.readValue(d.acceptedItemsJson, json.getTypeFactory().constructCollectionType(List.class, String.class));
            if (accepted.contains(suggestionId)) throw new BusinessException("该建议已经采纳");
            var next = saveVersion(parent.id, parsed);    // 生成新版本
            next.sourceDiagnosisId = d.id;
            next.sourceSuggestionId = suggestionId;       // 记录来源，便于追溯
            resumes.updateById(next);
            accepted.add(suggestionId);
            d.acceptedItemsJson = json.writeValueAsString(accepted);
            diagnoses.updateById(d);
            log.info("采纳成功: newVersionId={}, diagnosisId={}", next.id, diagnosisId);
            return new Acceptance(next, d);
        } catch (BusinessException e) {
            log.warn("采纳失败: diagnosisId={}, suggestionId={}, 原因={}", diagnosisId, suggestionId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("采纳失败: diagnosisId={}, suggestionId={}", diagnosisId, suggestionId, e);
            throw new BusinessException("采纳建议失败：" + e.getMessage());
        }
    }

    /**
     * 撤销采纳：只把建议从已采纳列表移除，历史版本保留。
     *
     * @param diagnosisId  诊断记录 id
     * @param suggestionId 建议 id
     * @return 更新后的诊断
     */
    @Transactional
    public ResumeDiagnosisEntity undo(long diagnosisId, String suggestionId) {
        log.info("撤销采纳: diagnosisId={}, suggestionId={}", diagnosisId, suggestionId);
        try {
            var d = requireDiagnosis(diagnosisId);
            List<String> accepted = json.readValue(d.acceptedItemsJson, json.getTypeFactory().constructCollectionType(List.class, String.class));
            if (!accepted.remove(suggestionId)) throw new BusinessException("该建议尚未采纳");
            d.acceptedItemsJson = json.writeValueAsString(accepted);
            diagnoses.updateById(d);
            log.info("撤销成功: diagnosisId={}, suggestionId={}", diagnosisId, suggestionId);
            return d;
        } catch (BusinessException e) {
            log.warn("撤销失败: diagnosisId={}, suggestionId={}, 原因={}", diagnosisId, suggestionId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("撤销失败: diagnosisId={}, suggestionId={}", diagnosisId, suggestionId, e);
            throw new BusinessException("撤销采纳失败：" + e.getMessage());
        }
    }

    /**
     * 列出与目标简历同属一条版本树的所有版本（按版本号、创建时间排序）。
     *
     * @param id 任意一个版本 id
     * @return 同链版本列表
     */
    public List<ResumeEntity> versions(long id) {
        var target = get(id);
        var all = list();
        long root = rootId(target, all);                  // 找到根版本
        return all.stream()
                .filter(r -> rootId(r, all) == root)      // 同根即同链
                .sorted(Comparator.comparing((ResumeEntity r) -> r.versionNo).thenComparing(r -> r.createdAt))
                .toList();
    }

    /**
     * 返回两个版本的简历实体与各自结构化 JSON，供前端做前后对比。
     *
     * @param beforeId 旧版本 id
     * @param afterId  新版本 id
     * @return 差异对象
     */
    public VersionDiff diff(long beforeId, long afterId) {
        log.info("简历版本对比: beforeId={}, afterId={}", beforeId, afterId);
        var before = get(beforeId);
        var after = get(afterId);
        try {
            return new VersionDiff(before, after, json.readTree(before.parsedJson), json.readTree(after.parsedJson));
        } catch (Exception e) {
            log.error("简历版本数据损坏: beforeId={}, afterId={}", beforeId, afterId, e);
            throw new BusinessException("简历版本数据损坏");
        }
    }

    /** 取诊断记录，并顺带校验其所属简历归属。 */
    private ResumeDiagnosisEntity requireDiagnosis(long id) {
        var d = diagnoses.selectById(id);
        if (d == null) throw new BusinessException("诊断记录不存在");
        get(d.resumeId);
        return d;
    }

    /** 沿 parentId 链走到根版本，返回根 id（同一条链的 root 相同）。 */
    private long rootId(ResumeEntity item, List<ResumeEntity> all) {
        var map = new java.util.HashMap<Long, ResumeEntity>();
        all.forEach(r -> map.put(r.id, r));
        var cursor = item;
        var seen = new java.util.HashSet<Long>();         // 防止环导致死循环
        while (cursor.parentId != null && seen.add(cursor.id) && map.containsKey(cursor.parentId)) {
            cursor = map.get(cursor.parentId);
        }
        return cursor.id;
    }

    /**
     * 下载简历原文件（走鉴权接口，不暴露 MinIO 直链）。
     *
     * @param id 简历 id
     * @return 文件名与内容流
     */
    public Download download(long id) {
        log.info("下载简历原文件: resumeId={}", id);
        var resume = get(id);
        if (resume.objectKey == null || resume.objectKey.isBlank()) throw new BusinessException("该简历没有可下载的原始文件");
        return new Download(resume.fileName, storage.get(resume.objectKey));
    }

    /** 校验简历文件：扩展名、大小、MIME。 */
    private void validate(MultipartFile f) {
        if (f == null || f.isEmpty()) throw new BusinessException("请选择简历文件");
        if (f.getSize() > 10 * 1024 * 1024) throw new BusinessException("简历文件不能超过 10MB");
        String n = f.getOriginalFilename() == null ? "" : f.getOriginalFilename().toLowerCase();
        if (!(n.endsWith(".pdf") || n.endsWith(".doc") || n.endsWith(".docx"))) throw new BusinessException("仅支持 PDF、DOC、DOCX");
        try {
            String mime = tika.detect(f.getInputStream(), f.getOriginalFilename());
            Set<String> allowed = Set.of("application/pdf", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/x-tika-msoffice", "application/x-tika-ooxml");
            if (!allowed.contains(mime)) throw new BusinessException("文件内容与 PDF/Word 扩展名不匹配");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("无法校验简历文件类型");
        }
    }

    /**
     * 下载载荷。
     *
     * @param fileName 文件名
     * @param content  内容流与元信息
     */
    public record Download(String fileName, ObjectStorageService.StoredContent content) { }

    /**
     * 采纳结果。
     *
     * @param version   生成的新版本
     * @param diagnosis 更新后的诊断
     */
    public record Acceptance(ResumeEntity version, ResumeDiagnosisEntity diagnosis) { }

    /**
     * 版本差异。
     *
     * @param before        旧版本实体
     * @param after         新版本实体
     * @param beforeContent 旧版结构化 JSON
     * @param afterContent  新版结构化 JSON
     */
    public record VersionDiff(ResumeEntity before, ResumeEntity after, JsonNode beforeContent, JsonNode afterContent) { }
}
