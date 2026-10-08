package com.careerorbit.ai;

import com.careerorbit.entity.AiCallLogEntity;
import com.careerorbit.mapper.AiCallLogMapper;
import com.careerorbit.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量（Embedding）网关：把文本转成向量，供 RAG 检索使用。
 *
 * <p>与 {@link ChatGateway} 一样是手写的 OpenAI 兼容调用，但拥有**独立的供应商、密钥与模型**。
 * 例如聊天用 DeepSeek、向量用通义的 text-embedding-v3，两者互不影响。</p>
 */
@Service
public class EmbeddingGateway {

    /** 向量服务基址，如 https://dashscope.aliyuncs.com/compatible-mode。 */
    private final String baseUrl;

    /** 向量服务的 API Key。 */
    private final String apiKey;

    /** 向量模型名，如 text-embedding-v3。 */
    private final String model;

    /** 配置开关，与 apiKey 非空共同决定能否调用。 */
    private final boolean configured;

    /** JSON 工具。 */
    private final ObjectMapper json;

    /** AI 调用日志 Mapper（可能为空）。 */
    private final AiCallLogMapper logs;

    /** 复用的 HTTP 客户端。 */
    private final HttpClient http;

    /** 单次请求超时。 */
    private final Duration timeout;

    /**
     * 构造函数：配置全部由 Spring 注入。
     *
     * @param logs           日志 Mapper（可空）
     * @param json           JSON 工具
     * @param baseUrl        向量服务基址
     * @param apiKey         API Key
     * @param model          向量模型名
     * @param configured     配置开关
     * @param timeoutSeconds 超时秒数
     */
    public EmbeddingGateway(
            ObjectProvider<AiCallLogMapper> logs,
            ObjectMapper json,
            @Value("${app.ai.embedding.base-url}") String baseUrl,
            @Value("${app.ai.embedding.api-key:}") String apiKey,
            @Value("${app.ai.embedding.model}") String model,
            @Value("${app.ai.embedding.configured:false}") boolean configured,
            @Value("${app.ai.embedding.timeout-seconds:60}") long timeoutSeconds) {
        this.logs = logs.getIfAvailable();
        this.json = json;
        this.baseUrl = baseUrl.replaceFirst("/+$", "");   // 去末尾斜杠
        this.apiKey = apiKey;
        this.model = model;
        this.configured = configured;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .build();
    }

    /**
     * 把一段文本转成向量。
     *
     * @param text 待向量化文本（一个分块或一条检索词）
     * @return 浮点数向量
     */
    public List<Double> embed(String text) {
        requireConfigured();
        long started = System.currentTimeMillis();
        try {
            // 请求体：model + input
            String body = json.writeValueAsString(Map.of("model", model, "input", text));
            String endpoint = baseUrl.endsWith("/v1") ? baseUrl + "/embeddings" : baseUrl + "/v1/embeddings";
            var request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new BusinessException("Embedding HTTP " + response.statusCode() + "：" + abbreviate(response.body()));
            }
            var values = responseBody(response.body());   // 从响应里取向量数组
            log(true, started, null);
            return values;
        } catch (BusinessException e) {
            log(false, started, e.getMessage());
            throw e;
        } catch (Exception e) {
            log(false, started, e.getClass().getSimpleName());
            throw new BusinessException("Embedding 调用失败：" + root(e));
        }
    }

    /**
     * 从响应 JSON 中取出 data[0].embedding 数组。
     * 单独抽出便于单元测试。
     *
     * @param body 响应体
     * @return 向量
     * @throws Exception JSON 解析异常
     */
    List<Double> responseBody(String body) throws Exception {
        var node = json.readTree(body).path("data").path(0).path("embedding");
        if (!node.isArray() || node.isEmpty()) throw new BusinessException("Embedding 服务返回空向量");
        List<Double> result = new ArrayList<>(node.size());
        node.forEach(v -> result.add(v.asDouble()));       // 逐元素转 double
        return result;
    }

    /** 是否可用：配置开关打开且 Key 非空。 */
    public boolean isConfigured() {
        return configured && !apiKey.isBlank();
    }

    /** 当前向量模型名。 */
    public String model() {
        return model;
    }

    /** 当前服务基址。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** 未配置时抛异常并给出配置指引。 */
    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BusinessException("Embedding 模型未配置，请设置 EMBEDDING_BASE_URL、EMBEDDING_API_KEY、EMBEDDING_MODEL 和 EMBEDDING_CONFIGURED=true");
        }
    }

    /** 截断超长文本，避免塞进异常/日志。 */
    private static String abbreviate(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 300));
    }

    /** 取最底层异常信息。 */
    private static String root(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** 记录一次向量调用（同样不记录原文内容）。 */
    private void log(boolean success, long started, String error) {
        if (logs == null) return;
        try {
            var e = new AiCallLogEntity();
            e.traceId = UUID.randomUUID().toString();
            e.provider = "EMBEDDING";
            e.model = model;
            e.operation = "embedding";
            e.inputTokens = 0;
            e.outputTokens = 0;
            e.latencyMs = System.currentTimeMillis() - started;
            e.success = success;
            e.errorCode = error == null ? null : abbreviate(error);
            e.createdAt = LocalDateTime.now();
            logs.insert(e);
        } catch (Exception ignored) {
        }
    }
}
