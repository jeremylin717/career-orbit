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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 视觉网关：识别图片里的文字（如招聘 App 中无法复制的岗位 JD）。
 *
 * <p>调用 OpenAI 兼容的多模态接口（默认通义 qwen-vl-max）。默认**复用 Embedding 的 DashScope
 * 地址与密钥**，也可用 VISION_* 环境变量单独指定。</p>
 */
@Service
public class VisionGateway {

    /** 视觉服务基址，默认继承 Embedding 的基址。 */
    private final String baseUrl;

    /** 视觉服务的 API Key，默认继承 Embedding 的 Key。 */
    private final String apiKey;

    /** 视觉模型名，如 qwen-vl-max。 */
    private final String model;

    /** 配置开关。 */
    private final boolean configured;

    /** JSON 工具。 */
    private final ObjectMapper json;

    /** AI 调用日志 Mapper（可能为空）。 */
    private final AiCallLogMapper logs;

    /** 复用的 HTTP 客户端。 */
    private final HttpClient http;

    /** 单次请求超时。 */
    private final Duration requestTimeout;

    /**
     * 构造函数：视觉配置的默认值在 application.yml 里指向 Embedding 的配置，
     * 因此只填了 DashScope 一处即可同时用于向量与视觉。
     *
     * @param logs           日志 Mapper（可空）
     * @param json           JSON 工具
     * @param baseUrl        视觉服务基址
     * @param apiKey         API Key
     * @param model          视觉模型名
     * @param configured     配置开关
     * @param timeoutSeconds 超时秒数
     */
    public VisionGateway(
            ObjectProvider<AiCallLogMapper> logs,
            ObjectMapper json,
            @Value("${app.ai.vision.base-url}") String baseUrl,
            @Value("${app.ai.vision.api-key:}") String apiKey,
            @Value("${app.ai.vision.model}") String model,
            @Value("${app.ai.vision.configured:false}") boolean configured,
            @Value("${app.ai.vision.timeout-seconds:60}") long timeoutSeconds) {
        this.logs = logs.getIfAvailable();
        this.json = json;
        this.baseUrl = baseUrl.replaceFirst("/+$", "");  // 去末尾斜杠
        this.apiKey = apiKey;
        this.model = model;
        this.configured = configured;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .build();
    }

    /** 是否可用：配置开关打开且 Key 非空。 */
    public boolean isConfigured() {
        return configured && !apiKey.isBlank();
    }

    /** 当前视觉模型名。 */
    public String model() {
        return model;
    }

    /** 当前服务基址。 */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 识别图片中的文字，返回纯文本。
     *
     * @param imageBytes  图片字节
     * @param contentType 图片 MIME，如 image/png
     * @return 识别出的文字（可能为空字符串）
     */
    public String extractText(byte[] imageBytes, String contentType) {
        requireConfigured();
        if (imageBytes == null || imageBytes.length == 0) throw new BusinessException("图片内容为空");
        if (imageBytes.length > 10 * 1024 * 1024) throw new BusinessException("图片不能超过 10MB");
        long started = System.currentTimeMillis();
        try {
            String mime = (contentType == null || contentType.isBlank()) ? "image/png" : contentType;
            // 多模态接口要求图片以 data URL 形式（base64）内嵌
            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
            String prompt = "你是招聘信息识别助手。请提取图片中的岗位 JD 文字内容，"
                    + "原样输出为纯文本，保留分点与换行结构，不要添加任何解释、标题、评论或多余说明。"
                    + "如果图片中不包含岗位信息，返回空字符串。";
            // content 是数组：一段文本 + 一张图片
            Map<String, Object> body = Map.of(
                    "model", model,
                    "temperature", 0.1,                       // 识别任务温度更低
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "text", "text", prompt),
                                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint("chat/completions")))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) throw new BusinessException("视觉模型 HTTP " + response.statusCode() + "：" + abbreviate(response.body()));
            String content = json.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText();
            log("vision_extract", true, started, null);
            return content == null ? "" : content.trim();
        } catch (BusinessException e) {
            log("vision_extract", false, started, e.getMessage());
            throw e;
        } catch (Exception e) {
            log("vision_extract", false, started, e.getClass().getSimpleName());
            throw new BusinessException("图片识别失败：" + root(e));
        }
    }

    /** 拼接完整端点。 */
    private String endpoint(String path) {
        return baseUrl.endsWith("/v1") ? baseUrl + "/" + path : baseUrl + "/v1/" + path;
    }

    /** 未配置时抛异常并给出配置指引。 */
    private void requireConfigured() {
        if (!isConfigured()) {
            throw new BusinessException("视觉模型未配置，请在 .env 设置 VISION_API_KEY 与 VISION_CONFIGURED=true（或复用 Embedding 的 DashScope 配置）");
        }
    }

    /** 截断超长文本。 */
    private static String abbreviate(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 300));
    }

    /** 取最底层异常信息。 */
    private static String root(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** 记录一次视觉调用。 */
    private void log(String operation, boolean success, long started, String error) {
        if (logs == null) return;
        try {
            var entry = new AiCallLogEntity();
            entry.traceId = UUID.randomUUID().toString();
            entry.provider = "VISION";
            entry.model = model;
            entry.operation = operation;
            entry.inputTokens = 0;
            entry.outputTokens = 0;
            entry.latencyMs = System.currentTimeMillis() - started;
            entry.success = success;
            entry.errorCode = error == null ? null : abbreviate(error);
            entry.createdAt = LocalDateTime.now();
            logs.insert(entry);
        } catch (Exception ignored) {
        }
    }
}
