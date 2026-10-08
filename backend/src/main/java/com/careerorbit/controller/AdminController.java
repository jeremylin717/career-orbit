package com.careerorbit.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.careerorbit.ai.*;
import com.careerorbit.entity.*;
import com.careerorbit.mapper.*;
import com.careerorbit.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理后台接口，整个类仅管理员可访问（@PreAuthorize）。
 * 含用户列表、AI 调用日志、三个模型网关状态、提示词增删查。
 */
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** 用户表 Mapper。 */
    private final UserMapper users;

    /** AI 调用日志 Mapper。 */
    private final AiCallLogMapper logs;

    /** 提示词模板 Mapper。 */
    private final PromptTemplateMapper prompts;

    /** 聊天网关（用于展示其配置状态）。 */
    private final ChatGateway chat;

    /** 向量网关（用于展示其配置状态）。 */
    private final EmbeddingGateway embedding;

    /** 视觉网关（用于展示其配置状态）。 */
    private final VisionGateway vision;

    public AdminController(UserMapper users, AiCallLogMapper logs, PromptTemplateMapper prompts, ChatGateway chat, EmbeddingGateway embedding, VisionGateway vision) {
        this.users = users;
        this.logs = logs;
        this.prompts = prompts;
        this.chat = chat;
        this.embedding = embedding;
        this.vision = vision;
    }

    /** 用户列表（只返回非敏感字段，不含密码哈希）。 */
    @GetMapping("/users")
    ApiResponse<?> users() {
        log.debug("接口调用 GET /api/admin/users");
        return ApiResponse.ok(users.selectList(new QueryWrapper<UserEntity>()
                .select("id", "email", "display_name", "role", "target_role", "created_at")
                .orderByDesc("created_at")));
    }

    /** 最近的 AI 调用日志（最多 200 条）。 */
    @GetMapping("/ai-calls")
    ApiResponse<?> calls() {
        log.debug("接口调用 GET /api/admin/ai-calls");
        return ApiResponse.ok(logs.selectList(new QueryWrapper<AiCallLogEntity>().orderByDesc("created_at").last("LIMIT 200")));
    }

    /** 三个模型网关的配置状态（apiKey 一律脱敏为 ***）。 */
    @GetMapping("/model")
    ApiResponse<?> model() {
        log.debug("接口调用 GET /api/admin/model");
        return ApiResponse.ok(Map.of(
                "chat", Map.of("configured", chat.isConfigured(), "model", chat.model(), "baseUrl", chat.baseUrl(), "apiKey", "***"),
                "embedding", Map.of("configured", embedding.isConfigured(), "model", embedding.model(), "baseUrl", embedding.baseUrl(), "apiKey", "***"),
                "vision", Map.of("configured", vision.isConfigured(), "model", vision.model(), "baseUrl", vision.baseUrl(), "apiKey", "***")));
    }

    @GetMapping("/prompts")
    ApiResponse<?> prompts() {
        log.debug("接口调用 GET /api/admin/prompts");
        return ApiResponse.ok(prompts.selectList(new QueryWrapper<PromptTemplateEntity>().orderByAsc("prompt_key")));
    }

    /** 按 promptKey 新增或更新一条提示词（存在则覆盖）。 */
    @PostMapping("/prompts")
    ApiResponse<?> savePrompt(@RequestBody PromptRequest request) {
        log.info("接口调用 POST /api/admin/prompts: promptKey={}", request.promptKey);
        var existing = prompts.selectOne(new QueryWrapper<PromptTemplateEntity>().eq("prompt_key", request.promptKey));
        var entity = existing == null ? new PromptTemplateEntity() : existing;
        entity.promptKey = request.promptKey;
        entity.name = request.name;
        entity.content = request.content;
        entity.enabled = request.enabled;
        entity.updatedAt = java.time.LocalDateTime.now();
        if (existing == null) prompts.insert(entity);
        else prompts.updateById(entity);
        return ApiResponse.ok(entity);
    }

    @DeleteMapping("/prompts/{id}")
    ApiResponse<?> deletePrompt(@PathVariable long id) {
        log.info("接口调用 DELETE /api/admin/prompts/{}", id);
        prompts.deleteById(id);
        return ApiResponse.ok(null);
    }

    record PromptRequest(String promptKey, String name, String content, Boolean enabled) { }
}
