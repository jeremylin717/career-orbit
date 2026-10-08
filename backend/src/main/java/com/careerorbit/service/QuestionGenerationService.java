package com.careerorbit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.careerorbit.ai.ChatGateway;
import com.careerorbit.ai.PromptTemplates;
import com.careerorbit.entity.QuestionSetEntity;
import com.careerorbit.mapper.QuestionSetMapper;
import com.careerorbit.security.CurrentUser;
import com.careerorbit.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 面试题生成服务。
 * 知识库是“增强项”而非硬依赖：检索到就作为参考并标注来源；检索不到就降级为仅依据简历与 JD 生成。
 */
@Service
public class QuestionGenerationService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationService.class);

    /** 简历服务（取候选人简历 JSON）。 */
    private final ResumeService resumes;

    /** RAG 服务（检索相关知识用于出题）。 */
    private final RagService rag;

    /** 聊天网关（生成题目）。 */
    private final ChatGateway chat;

    /** 题目集 Mapper。 */
    private final QuestionSetMapper sets;

    /** 当前登录用户。 */
    private final CurrentUser current;

    /** JSON 工具。 */
    private final ObjectMapper json;

    public QuestionGenerationService(ResumeService resumes, RagService rag, ChatGateway chat, QuestionSetMapper sets, CurrentUser current, ObjectMapper json) {
        this.resumes = resumes;
        this.rag = rag;
        this.chat = chat;
        this.sets = sets;
        this.current = current;
        this.json = json;
    }

    /**
     * 生成一套个性化面试题。
     *
     * @param c 生成指令（简历 id、岗位信息、技术方向、难度、题数、题型）
     * @return 已保存的题目集
     */
    public QuestionSetEntity generate(GenerateCommand c) {
        log.info("生成面试题: resumeId={}, jobTitle={}, technology={}, difficulty={}, count={}",
                c.resumeId(), c.jobTitle(), c.technology(), c.difficulty(), c.count());
        try {
            var resume = resumes.get(c.resumeId());

            // 知识库降级：检索失败或为空时，仅依据简历+JD 生成，不阻断出题。
            List<RagService.RagHit> sources;
            try {
                sources = rag.search(c.jobTitle() + " " + c.jobDescription() + " " + c.technology() + " " + resume.parsedJson, Math.max(8, c.count()));
            } catch (BusinessException ex) {
                log.warn("出题前检索失败，降级为无知识库生成: 原因={}", ex.getMessage());
                sources = List.of();
            }
            // 有检索结果就作为参考上下文；没有则明确告知模型不要虚构来源
            String context = sources.isEmpty()
                    ? "（知识库暂无相关文档。请仅依据候选人真实简历与岗位 JD 生成题目，不得虚构候选人经历；sourceChunkIds 请留空数组。）"
                    : json.writeValueAsString(sources);

            String config = json.writeValueAsString(c);
            List<Map<String, Object>> generated = chat.json(
                    PromptTemplates.questions(resume.parsedJson, config, context),
                    new TypeReference<>() { });

            // 按题面去重，避免同一道题重复（LinkedHashMap 保序）
            var unique = new LinkedHashMap<String, Map<String, Object>>();
            for (var q : generated) {
                String text = Objects.toString(q.get("question"), "").trim();
                if (!text.isBlank()) unique.putIfAbsent(text, q);
            }
            if (unique.isEmpty()) throw new BusinessException("模型未生成有效题目");

            var e = new QuestionSetEntity();
            e.userId = current.require().id;
            e.resumeId = c.resumeId();
            e.title = c.jobTitle() + " · " + c.difficulty();
            e.jobTitle = c.jobTitle();
            e.jobDescription = c.jobDescription();
            e.configJson = config;
            e.questionsJson = json.writeValueAsString(unique.values().stream().limit(c.count()).toList());  // 截到目标题数
            e.createdAt = LocalDateTime.now();
            sets.insert(e);
            log.info("题目生成成功: questionSetId={}, 模型返回={}, 去重后入库={}", e.id, generated.size(), unique.size());
            return e;
        } catch (BusinessException e) {
            log.warn("题目生成失败: resumeId={}, 原因={}", c.resumeId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("题目生成失败: resumeId={}", c.resumeId(), e);
            throw new BusinessException("题目生成失败：" + e.getMessage());
        }
    }

    /** 当前用户的题目集列表。 */
    public List<QuestionSetEntity> list() {
        return sets.selectList(new QueryWrapper<QuestionSetEntity>().eq("user_id", current.require().id).orderByDesc("created_at"));
    }

    /**
     * 取题目集并做归属校验。
     *
     * @param id 题目集 id
     * @return 题目集
     */
    public QuestionSetEntity get(long id) {
        var e = sets.selectById(id);
        if (e == null || !e.userId.equals(current.require().id)) throw new BusinessException("题目集不存在");
        return e;
    }

    /** 删除题目集（先校验归属）。 */
    public void delete(long id) {
        get(id);
        sets.deleteById(id);
    }

    /**
     * 生成指令。
     *
     * @param resumeId       简历 id
     * @param jobTitle       岗位名称
     * @param jobDescription 岗位 JD 全文
     * @param technology     技术方向
     * @param difficulty     难度
     * @param count          题目数量
     * @param types          题型列表
     */
    public record GenerateCommand(long resumeId, String jobTitle, String jobDescription, String technology,
                                  String difficulty, int count, List<String> types) { }
}
