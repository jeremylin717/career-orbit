package com.careerorbit.ai;

import com.careerorbit.entity.AiCallLogEntity;
import com.careerorbit.mapper.AiCallLogMapper;
import com.careerorbit.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 聊天网关：对接任意 OpenAI 兼容的 Chat 模型（如 DeepSeek）。
 *
 * <p>设计要点：这就是“不依赖 Spring AI、自己手写模型调用”的核心。
 * 它只用 JDK 自带的 {@link HttpClient} 发 HTTP 请求，因此不引入额外依赖，
 * 也刻意不读取任何 Embedding 配置——聊天与向量是两个完全独立的模型服务。</p>
 */
@Service
public class ChatGateway {

    /** 聊天服务的基址，如 https://api.deepseek.com（末尾斜杠会被去掉）。 */
    private final String baseUrl;

    /** 调用模型用的 API Key（Bearer 认证）。 */
    private final String apiKey;

    /** 模型名，如 deepseek-chat。 */
    private final String model;

    /** 是否“已配置”开关（来自配置项），与 apiKey 非空共同决定能否调用。 */
    private final boolean configured;

    /** JSON 序列化/反序列化工具（Spring 注入，全局共享）。 */
    private final ObjectMapper json;

    /**
     * AI 调用日志的 Mapper。
     * 用 {@link ObjectProvider} 获取是因为测试环境下可能没有该 Bean，允许为 null。
     */
    private final AiCallLogMapper logs;

    /** 复用的 HTTP 客户端（连接池级别复用，避免每次调用都新建）。 */
    private final HttpClient http;

    /** 单次模型请求的超时时间。 */
    private final Duration requestTimeout;

    /**
     * 构造函数：所有配置由 Spring 从 application.yml / 环境变量注入。
     *
     * @param logs           日志 Mapper（可能为空）
     * @param json           JSON 工具
     * @param baseUrl        聊天服务基址
     * @param apiKey         API Key
     * @param model          模型名
     * @param configured     配置开关
     * @param timeoutSeconds 超时秒数
     */
    public ChatGateway(
            ObjectProvider<AiCallLogMapper> logs,
            ObjectMapper json,
            @Value("${app.ai.chat.base-url}") String baseUrl,
            @Value("${app.ai.chat.api-key:}") String apiKey,
            @Value("${app.ai.chat.model}") String model,
            @Value("${app.ai.chat.configured:false}") boolean configured,
            @Value("${app.ai.chat.timeout-seconds:60}") long timeoutSeconds) {
        this.logs = logs.getIfAvailable();               // 没有日志 Bean 时为 null
        this.json = json;
        this.baseUrl = trimSlash(baseUrl);               // 去掉末尾 "/" 便于拼接路径
        this.apiKey = apiKey;
        this.model = model;
        this.configured = configured;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        // 连接超时最多 10 秒，避免网络不通时长时间卡住
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .build();
    }

    /**
     * 一次性调用聊天模型，返回完整回答文本（非流式）。
     *
     * @param prompt 提示词
     * @return 模型返回的文本（已去掉可能的 ```json 围栏）
     */
    public String chat(String prompt) {
        requireConfigured();                              // 未配置直接报错
        long started = System.currentTimeMillis();        // 记录开始时间，用于计算耗时
        try {
            // 组装 OpenAI 兼容的请求体：model + messages + temperature + 非流式
            String body = json.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.2,                    // 低温，减少随机性，适合结构化输出
                    "stream", false));
            HttpResponse<String> response = http.send(request(body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body());   // 非 2xx 抛业务异常
            // 从 choices[0].message.content 取出回答
            String content = json.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) throw new BusinessException("聊天模型返回空内容");
            log("chat", true, started, null);             // 记一条成功日志
            return clean(content);                        // 清理代码围栏后返回
        } catch (BusinessException e) {
            log("chat", false, started, e.getMessage());  // 记一条失败日志
            throw e;
        } catch (Exception e) {
            log("chat", false, started, e.getClass().getSimpleName());
            throw new BusinessException("聊天模型调用失败：" + root(e));
        }
    }

    /**
     * 流式调用：边接收模型输出边回调，用于 SSE 场景（如面试官逐字提问）。
     *
     * @param prompt    提示词
     * @param onDelta   每收到一个增量片段就回调一次
     * @param cancelled 是否已被取消（如前端断开）；返回 true 时提前结束读取
     */
    public void stream(String prompt, Consumer<String> onDelta, BooleanSupplier cancelled) {
        requireConfigured();
        long started = System.currentTimeMillis();
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.2,
                    "stream", true));                     // 开启流式
            HttpResponse<java.io.InputStream> response = http.send(request(body), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300) {
                // 出错时把响应体读出来，便于定位问题
                ensureSuccess(response.statusCode(), new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;                              // 逐行读取 SSE
                while (!cancelled.getAsBoolean() && (line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;        // 只处理 data 行
                    String data = line.substring(5).trim();         // 去掉 "data:" 前缀
                    if (data.isBlank() || "[DONE]".equals(data)) continue;  // 结束标记跳过
                    JsonNode event = json.readTree(data);
                    String delta = event.path("choices").path(0).path("delta").path("content").asText();
                    if (!delta.isEmpty()) onDelta.accept(delta);    // 回调增量
                }
            }
            log("chat_stream", true, started, null);
        } catch (BusinessException e) {
            log("chat_stream", false, started, e.getMessage());
            throw e;
        } catch (Exception e) {
            log("chat_stream", false, started, e.getClass().getSimpleName());
            throw new BusinessException("聊天模型流式调用失败：" + root(e));
        }
    }

    /**
     * 调用模型并把文本解析为指定类型（如某个 DTO 类）。
     *
     * @param prompt 提示词
     * @param type   目标类型
     * @return 反序列化后的对象
     */
    public <T> T json(String prompt, Class<T> type) {
        try {
            return json.readValue(chat(prompt), type);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("聊天模型返回的结构化 JSON 无法解析");
        }
    }

    /**
     * 同 {@link #json(String, Class)}，用于泛型类型（如 List&lt;Map&lt;String,Object&gt;&gt;）。
     *
     * @param prompt 提示词
     * @param type   TypeReference 携带的泛型类型
     * @return 反序列化后的对象
     */
    public <T> T json(String prompt, TypeReference<T> type) {
        try {
            return json.readValue(chat(prompt), type);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("聊天模型返回的结构化 JSON 无法解析");
        }
    }

    /** 是否可用：既开了配置开关，又填了非空 Key。 */
    public boolean isConfigured() {
        return configured && !apiKey.isBlank();
    }

    /** 当前使用的模型名。 */
    public String model() {
        return model;
    }

    /** 当前使用的服务基址（供管理后台展示）。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** 构造 POST 请求：指向 chat/completions，带 Bearer 认证与 JSON 头。 */
    private HttpRequest request(String body) {
        return HttpRequest.newBuilder(URI.create(endpoint("chat/completions")))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /** 拼接完整端点；若基址已含 /v1 则不重复添加。 */
    private String endpoint(String path) {
        return baseUrl.endsWith("/v1") ? baseUrl + "/" + path : baseUrl + "/v1/" + path;
    }

    /** 未配置时抛异常并给出清晰的配置指引。 */
    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BusinessException("聊天模型未配置，请设置 CHAT_BASE_URL、CHAT_API_KEY、CHAT_MODEL 和 CHAT_CONFIGURED=true");
        }
    }

    /** HTTP 状态码 >= 300 视为失败，抛业务异常并附带响应片段。 */
    private void ensureSuccess(int status, String body) {
        if (status >= 300) throw new BusinessException("聊天模型 HTTP " + status + "：" + abbreviate(body));
    }

    /** 去掉模型可能包裹的 ```json ... ``` 代码围栏。 */
    private String clean(String value) {
        return value.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    }

    /** 去掉 URL 末尾的斜杠。 */
    private static String trimSlash(String value) {
        return value.replaceFirst("/+$", "");
    }

    /** 截断字符串，避免把超长响应体塞进异常或日志。 */
    private static String abbreviate(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 300));
    }

    /** 取最底层异常信息，让错误提示更贴近根因。 */
    private static String root(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /**
     * 记录一次 AI 调用。
     * 安全约束：只记录模型名、耗时、成功与错误摘要，**绝不记录提示词/简历/回答原文**。
     */
    private void log(String operation, boolean success, long started, String error) {
        if (logs == null) return;                         // 无日志 Mapper 时静默跳过
        try {
            var entry = new AiCallLogEntity();
            entry.traceId = UUID.randomUUID().toString(); // 每次调用一个追踪号
            entry.provider = "CHAT";                      // 来源网关
            entry.model = model;
            entry.operation = operation;                  // chat / chat_stream
            entry.inputTokens = 0;                        // 当前未解析 usage，占位为 0
            entry.outputTokens = 0;
            entry.latencyMs = System.currentTimeMillis() - started;
            entry.success = success;
            entry.errorCode = error == null ? null : abbreviate(error);
            entry.createdAt = LocalDateTime.now();
            logs.insert(entry);
        } catch (Exception ignored) {
            // 日志失败不能影响主流程，直接忽略
        }
    }
}
