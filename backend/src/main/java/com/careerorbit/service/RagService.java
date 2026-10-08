package com.careerorbit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.careerorbit.ai.EmbeddingGateway;
import com.careerorbit.entity.*;
import com.careerorbit.mapper.*;
import com.careerorbit.storage.ObjectStorageService;
import com.careerorbit.common.BusinessException;
import com.fasterxml.jackson.databind.*;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * RAG 知识库核心服务。
 *
 * <p>入库链路：文档 -> MinIO 存原文件 -> Tika 抽文本 -> 滑窗分块 -> 每块向量化 -> 写入 Elasticsearch。
 * 检索链路：BM25 关键词检索 + 向量语义检索，两路各自取候选，再用 RRF 融合排序去重。
 * 向量库选型为 Elasticsearch（dense_vector 字段做语义、text 字段做全文）。</p>
 */
@Service
public class RagService {

    /** 日志。注意：绝不记录知识文档原文。 */
    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 知识文档表 Mapper（文档级元数据）。 */
    private final KnowledgeDocumentMapper documents;

    /** 知识分块表 Mapper（块级正文与向量 id）。 */
    private final KnowledgeChunkMapper chunks;

    /** 对象存储（存放上传的原始文档）。 */
    private final ObjectStorageService storage;

    /** 向量网关（把文本转成向量）。 */
    private final EmbeddingGateway embedding;

    /** JSON 工具。 */
    private final ObjectMapper json;

    /** Elasticsearch 基址。 */
    private final String esUrl;

    /** ES 索引名。 */
    private final String index;

    /** ES 专用 HTTP 客户端（连接超时可配）。 */
    private final HttpClient http;

    /** ES 读超时（单次请求）。 */
    private final Duration readTimeout;

    /** Tika 解析器（把 PDF/Word 等抽取为纯文本）。 */
    private final Tika tika = new Tika();

    /**
     * 构造函数：ES 相关配置由 application.yml 注入。
     *
     * @param documents      文档 Mapper
     * @param chunks         分块 Mapper
     * @param storage        对象存储
     * @param embedding      向量网关
     * @param json           JSON 工具
     * @param esUrl          ES 基址
     * @param index          索引名
     * @param connectTimeout ES 连接超时（秒）
     * @param readTimeout    ES 读超时（秒）
     */
    public RagService(
            KnowledgeDocumentMapper documents,
            KnowledgeChunkMapper chunks,
            ObjectStorageService storage,
            EmbeddingGateway embedding,
            ObjectMapper json,
            @Value("${app.elasticsearch.url}") String esUrl,
            @Value("${app.elasticsearch.index}") String index,
            @Value("${app.elasticsearch.connect-timeout-seconds:5}") long connectTimeout,
            @Value("${app.elasticsearch.read-timeout-seconds:15}") long readTimeout) {
        this.documents = documents;
        this.chunks = chunks;
        this.storage = storage;
        this.embedding = embedding;
        this.json = json;
        this.esUrl = esUrl.replaceFirst("/+$", "");      // 去末尾斜杠
        this.index = index;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeout)).build();
        this.readTimeout = Duration.ofSeconds(readTimeout);
    }

    /**
     * 上传知识文档并完成入库。任一步失败都会清理残留并把文档标记为 FAILED。
     *
     * @param file       上传的文件
     * @param category   分类（面试题/参考答案/评分标准/技术文档）
     * @param technology 技术方向
     * @param difficulty 难度
     * @return 入库后的文档实体（状态 READY）
     */
    public KnowledgeDocumentEntity upload(MultipartFile file, String category, String technology, String difficulty) {
        log.info("上传知识文档: fileName={}, category={}, technology={}, difficulty={}",
                file == null ? null : file.getOriginalFilename(), category, technology, difficulty);
        validate(file);
        ObjectStorageService.StoredObject object = null;  // 记录已存的对象，失败时用于回滚
        KnowledgeDocumentEntity d = null;                 // 文档实体，失败时用于标记 FAILED
        try {
            object = storage.put(file, "knowledge");       // 原文件存 MinIO
            d = new KnowledgeDocumentEntity();
            d.title = file.getOriginalFilename();
            d.category = category;
            d.difficulty = difficulty;
            d.sourceUri = object.url();
            d.objectKey = object.key();
            d.status = "PROCESSING";
            d.createdAt = LocalDateTime.now();
            documents.insert(d);                           // 先落一条 PROCESSING 记录

            String text = tika.parseToString(file.getInputStream());   // 抽取纯文本
            if (text.isBlank()) throw new BusinessException("知识文档没有可解析文字");

            ensureIndex();                                 // 确保 ES 索引存在
            int i = 0;                                     // 分块序号
            for (String part : split(text, 800, 120)) {    // 每块 800 字，重叠 120
                var c = new KnowledgeChunkEntity();
                c.documentId = d.id;
                c.chunkIndex = i++;
                c.content = part;
                c.category = category;
                c.technology = technology;
                c.difficulty = difficulty;
                c.source = d.sourceUri;
                c.vectorId = UUID.randomUUID().toString(); // 每块一个 ES 文档 id
                c.createdAt = LocalDateTime.now();
                chunks.insert(c);                          // 存分块
                index(c, d.title, embedding.embed(part));  // 向量化并写入 ES（标题复用，避免回查）
            }
            refreshIndex();                                // 全部写完后统一刷新一次
            d.status = "READY";
            d.errorReason = null;
            documents.updateById(d);
            log.info("知识文档入库成功: documentId={}, 分块数={}", d.id, i);
            return d;
        } catch (Exception e) {
            String reason = root(e);
            if (d != null && d.id != null) {
                // 已插入文档：清理已写入的向量与分块，并标记失败
                try { deleteVectors(d.id); } catch (Exception ignored) { }
                try { chunks.delete(new QueryWrapper<KnowledgeChunkEntity>().eq("document_id", d.id)); } catch (Exception ignored) { }
                d.status = "FAILED";
                d.errorReason = abbreviate(reason);
                try { documents.updateById(d); } catch (Exception ignored) { }
            } else if (object != null) {
                // 尚未插入文档：删掉刚存的文件，避免残留
                try { storage.delete(object.key()); } catch (Exception ignored) { }
            }
            log.error("知识文档入库失败: fileName={}, 原因={}", file == null ? null : file.getOriginalFilename(), reason, e);
            if (e instanceof BusinessException b) throw b;
            throw new BusinessException("知识文档入库失败：" + reason);
        }
    }

    /** 列出所有知识文档（按创建时间倒序）。 */
    public List<KnowledgeDocumentEntity> documents() {
        return documents.selectList(new QueryWrapper<KnowledgeDocumentEntity>().orderByDesc("created_at"));
    }

    /** 分块总数（用于后台统计）。 */
    public long chunkCount() {
        return chunks.selectCount(null);
    }

    /** 删除文档：清 ES 向量 -> 删分块记录 -> 删 MinIO 文件 -> 删文档。 */
    public void delete(long id) {
        log.info("删除知识文档: documentId={}", id);
        var d = documents.selectById(id);
        if (d == null) {
            log.warn("删除失败，文档不存在: documentId={}", id);
            throw new BusinessException("知识文档不存在");
        }
        try {
            deleteVectors(id);
            chunks.delete(new QueryWrapper<KnowledgeChunkEntity>().eq("document_id", id));
            storage.delete(d.objectKey);
            documents.deleteById(id);
            log.info("删除成功: documentId={}", id);
        } catch (Exception e) {
            d.status = "FAILED";
            d.errorReason = "删除失败：" + abbreviate(root(e));
            documents.updateById(d);
            log.error("删除失败: documentId={}", id, e);
            throw e instanceof BusinessException b ? b : new BusinessException(d.errorReason);
        }
    }

    /** 重新向量化：先删旧向量，再对每个分块重新 embed 并写回（知识库换模型后可重建）。 */
    public KnowledgeDocumentEntity reindex(long id) {
        log.info("重新向量化: documentId={}", id);
        var d = documents.selectById(id);
        if (d == null) throw new BusinessException("知识文档不存在");
        d.status = "PROCESSING";
        d.errorReason = null;
        documents.updateById(d);
        try {
            ensureIndex();
            deleteVectors(id);                            // 删旧向量
            var doc = documents.selectById(id);           // 一次取出标题，供所有分块复用
            for (var c : chunks.selectList(new QueryWrapper<KnowledgeChunkEntity>().eq("document_id", id).orderByAsc("chunk_index"))) {
                String newVectorId = UUID.randomUUID().toString();
                String old = c.vectorId;                  // 备份旧 id，失败时回滚
                c.vectorId = newVectorId;
                try {
                    index(c, doc.title, embedding.embed(c.content));
                    chunks.updateById(c);
                } catch (Exception e) {
                    c.vectorId = old;
                    throw e;
                }
            }
            refreshIndex();                               // 全部写完后统一刷新一次
            d.status = "READY";
            documents.updateById(d);
            log.info("重新向量化成功: documentId={}", id);
            return d;
        } catch (Exception e) {
            d.status = "FAILED";
            d.errorReason = "重建失败：" + abbreviate(root(e));
            documents.updateById(d);
            log.error("重新向量化失败: documentId={}", id, e);
            throw e instanceof BusinessException b ? b : new BusinessException(d.errorReason);
        }
    }

    /**
     * 混合检索：BM25 关键词 + 向量语义，两路各取候选，再用 RRF 融合去重。
     * 这是出题和评价前“先检索、再喂给模型”的关键一步。
     *
     * @param query 检索词
     * @param topK  返回条数
     * @return 融合排序后的命中列表
     */
    public List<RagHit> search(String query, int topK) {
        if (query == null || query.isBlank()) throw new BusinessException("检索词不能为空");
        ensureIndex();
        var vector = embedding.embed(query);              // 把检索词也向量化

        // 第一路：BM25 全文检索（content 权重更高）
        List<RagHit> bm25 = searchEs(Map.of(
                "size", Math.min(50, topK * 3),
                "query", Map.of("multi_match", Map.of(
                        "query", query,
                        "fields", List.of("content^2", "technology", "category")))));

        // 第二路：向量语义检索（script_score + 余弦相似度）
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("source", "cosineSimilarity(params.query_vector, 'embedding') + 1.0");
        script.put("params", Map.of("query_vector", vector));
        List<RagHit> semantic = searchEs(Map.of(
                "size", Math.min(50, topK * 3),
                "query", Map.of("script_score", Map.of(
                        "query", Map.of("match_all", Map.of()),
                        "script", script))));

        List<RagHit> hits = rrf(bm25, semantic, topK);    // 融合
        // 只记录条数与候选数，不记录检索词（其中可能包含简历内容）
        log.info("RAG 检索: 检索词长度={}, topK={}, BM25候选={}, 向量候选={}, 融合结果={}",
                query.length(), topK, bm25.size(), semantic.size(), hits.size());
        return hits;
    }

    /**
     * 执行一次 ES _search 并把命中解析为 RagHit 列表。
     *
     * @param body ES 查询体
     * @return 命中列表
     */
    private List<RagHit> searchEs(Object body) {
        try {
            JsonNode root = json.readTree(request("POST", "/" + index + "/_search", json.writeValueAsString(body)));
            List<RagHit> out = new ArrayList<>();
            for (JsonNode h : root.path("hits").path("hits")) {   // 遍历命中数组
                JsonNode s = h.path("_source");                    // 命中文档的字段
                out.add(new RagHit(
                        h.path("_id").asText(),                    // ES 文档 id（即 vectorId）
                        s.path("documentId").asLong(),
                        s.path("title").asText(),
                        s.path("content").asText(),
                        s.path("category").asText(),
                        s.path("technology").asText(),
                        s.path("difficulty").asText(),
                        s.path("source").asText(),
                        h.path("_score").asDouble()));
            }
            return out;
        } catch (Exception e) {
            throw new BusinessException("Elasticsearch 检索失败：" + root(e));
        }
    }

    /**
     * Reciprocal Rank Fusion：按排名倒数（1/(60+rank)）累加两路得分并排序去重。
     * 常数 61 = 60 + 1（rank 从 0 开始），是 RRF 的常用平滑项。
     *
     * @param a 第一路结果（BM25）
     * @param b 第二路结果（向量）
     * @param k 返回条数
     * @return 融合后的结果
     */
    List<RagHit> rrf(List<RagHit> a, List<RagHit> b, int k) {
        Map<String, Double> score = new HashMap<>();      // chunkId -> 累加得分
        Map<String, RagHit> values = new HashMap<>();     // chunkId -> 原始命中
        for (int i = 0; i < a.size(); i++) {
            score.merge(a.get(i).chunkId(), 1d / (61 + i), Double::sum);
            values.put(a.get(i).chunkId(), a.get(i));
        }
        for (int i = 0; i < b.size(); i++) {
            score.merge(b.get(i).chunkId(), 1d / (61 + i), Double::sum);
            values.put(b.get(i).chunkId(), b.get(i));
        }
        return score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())   // 得分高的在前
                .limit(k)
                .map(e -> {
                    var h = values.get(e.getKey());
                    return new RagHit(h.chunkId, h.documentId, h.title, h.content,
                            h.category, h.technology, h.difficulty, h.source, e.getValue());  // 用融合分替换
                })
                .toList();
    }

    /** 确保索引存在；不存在则按映射创建（向量维度在运行时由 Embedding 探测）。 */
    private void ensureIndex() {
        try {
            var head = http.send(HttpRequest.newBuilder(URI.create(esUrl + "/" + index))
                    .timeout(readTimeout)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())   // HEAD 探测是否存在
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (head.statusCode() == 404) {
                // 索引不存在：按字段映射创建
                var mapping = Map.of("mappings", Map.of("properties", Map.of(
                        "documentId", Map.of("type", "long"),
                        "content", Map.of("type", "text"),
                        "embedding", Map.of("type", "dense_vector",
                                "dims", embedding.embed("维度检测").size(),   // 运行时探测维度
                                "index", true, "similarity", "cosine"),
                        "technology", Map.of("type", "keyword"),
                        "category", Map.of("type", "keyword"),
                        "difficulty", Map.of("type", "keyword"))));
                request("PUT", "/" + index, json.writeValueAsString(mapping));
            } else if (head.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + head.statusCode());
            }
        } catch (Exception e) {
            throw new BusinessException("Elasticsearch 不可用：" + root(e));
        }
    }

    /**
     * 把单个分块（含向量）写入 ES，文档 id 用 vectorId 保证唯一。
     * 不再逐块 refresh（那会让每次写入都强制刷新索引、极其缓慢）；标题由调用方传入，避免逐块回查数据库。
     */
    private void index(KnowledgeChunkEntity c, String title, List<Double> vector) {
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("documentId", c.documentId);
            body.put("title", title);
            body.put("content", c.content);
            body.put("category", c.category);
            body.put("technology", c.technology);
            body.put("difficulty", c.difficulty);
            body.put("source", c.source);
            body.put("embedding", vector);
            request("PUT", "/" + index + "/_doc/" + c.vectorId, json.writeValueAsString(body));
        } catch (Exception e) {
            throw new BusinessException("向量写入失败：" + root(e));
        }
    }

    /** 批量写入后统一刷新索引一次，让新文档立即可被检索到；失败不影响入库结果。 */
    private void refreshIndex() {
        try {
            request("POST", "/" + index + "/_refresh", null);
        } catch (Exception ignored) {
            // 刷新失败只是新文档稍晚可检索（ES 默认 1 秒后自动刷新），不应让整个入库失败
        }
    }

    /** 按 documentId 删除该文档的所有向量（忽略索引不存在时的 404）。 */
    private void deleteVectors(long documentId) {
        try {
            String body = json.writeValueAsString(Map.of("query", Map.of("term", Map.of("documentId", documentId))));
            request("POST", "/" + index + "/_delete_by_query?refresh=true&conflicts=proceed", body);
        } catch (Exception e) {
            if (e instanceof BusinessException b && b.getMessage().contains("HTTP 404")) return;
            throw e instanceof BusinessException b ? b : new BusinessException("清理旧向量失败：" + root(e));
        }
    }

    /** 统一的 ES HTTP 调用封装，附带超时与错误转换。 */
    private String request(String method, String path, String body) {
        try {
            var b = HttpRequest.newBuilder(URI.create(esUrl + path))
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() >= 300 && r.statusCode() != 404) {
                throw new IllegalStateException("HTTP " + r.statusCode() + " " + r.body());
            }
            if (r.statusCode() == 404) throw new BusinessException("Elasticsearch HTTP 404：" + r.body());
            return r.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Elasticsearch 请求失败：" + root(e));
        }
    }

    /** 校验知识文档：扩展名、大小、MIME。md/txt 为纯文本只按扩展名放行；PDF/Word 才做 MIME 校验。 */
    private void validate(MultipartFile f) {
        if (f == null || f.isEmpty()) throw new BusinessException("请选择知识文档");
        if (f.getSize() > 20 * 1024 * 1024) throw new BusinessException("知识文档不能超过 20MB");
        String n = Objects.toString(f.getOriginalFilename(), "").toLowerCase();
        boolean isMd = n.endsWith(".md"), isTxt = n.endsWith(".txt");
        if (!(n.endsWith(".pdf") || n.endsWith(".doc") || n.endsWith(".docx") || isTxt || isMd)) {
            throw new BusinessException("知识文档仅支持 PDF、Word、TXT、Markdown");
        }
        if (isMd || isTxt) return;                        // 纯文本不再做 MIME 校验
        try {
            String mime = tika.detect(f.getInputStream(), f.getOriginalFilename());
            var allowed = Set.of("application/pdf", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/x-tika-msoffice", "application/x-tika-ooxml");
            if (!allowed.contains(mime)) throw new BusinessException("知识文档内容与扩展名不匹配");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("无法校验知识文档类型");
        }
    }

    /**
     * 固定长度滑窗分块：每块 size 个字符，块间重叠 overlap，避免语义在切点被切断。
     *
     * @param raw     原始文本
     * @param size    块大小
     * @param overlap 相邻块重叠字符数
     * @return 分块列表
     */
    private List<String> split(String raw, int size, int overlap) {
        String text = raw.replaceAll("\\s+", " ").trim();   // 归一化空白
        List<String> out = new ArrayList<>();
        for (int start = 0; start < text.length(); start += size - overlap) {
            out.add(text.substring(start, Math.min(text.length(), start + size)));
        }
        return out;
    }

    /** 取最底层异常信息。 */
    private String root(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** 截断超长文本，避免塞进异常或数据库字段。 */
    private String abbreviate(String v) {
        return v == null ? "" : v.substring(0, Math.min(900, v.length()));
    }

    /**
     * 检索命中结果。
     *
     * @param chunkId   分块在 ES 中的文档 id
     * @param documentId 所属文档 id
     * @param title     文档标题
     * @param content   分块正文
     * @param category  分类
     * @param technology 技术方向
     * @param difficulty 难度
     * @param source    来源
     * @param score     融合后的得分
     */
    public record RagHit(String chunkId, long documentId, String title, String content,
                         String category, String technology, String difficulty, String source, double score) { }

    /**
     * 知识库统计。
     *
     * @param documents 文档数
     * @param chunks    分块数
     */
    public record Stats(long documents, long chunks) { }
}
