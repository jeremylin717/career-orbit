package com.careerorbit.controller;

import com.careerorbit.ai.ChatGateway;
import com.careerorbit.common.*;
import com.careerorbit.service.InterviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模拟面试接口。
 * 其中 /question-stream 用 SSE 把面试官提问的模型输出逐字流式推给前端。
 */
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    /** 日志。注意：绝不记录用户回答原文。 */
    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);

    /** 面试业务服务。 */
    private final InterviewService service;

    /** 聊天网关（用于 SSE 流式提问）。 */
    private final ChatGateway chat;

    public InterviewController(InterviewService service, ChatGateway chat) {
        this.service = service;
        this.chat = chat;
    }

    @PostMapping
    ApiResponse<?> create(@Valid @RequestBody CreateRequest r) {
        log.info("接口调用 POST /api/interviews: questionSetId={}", r.questionSetId());
        return ApiResponse.ok(service.create(r.questionSetId));
    }

    @GetMapping
    ApiResponse<?> history() {
        log.debug("接口调用 GET /api/interviews");
        return ApiResponse.ok(service.history());
    }

    @GetMapping("/{id}")
    ApiResponse<?> get(@PathVariable String id) {
        log.debug("接口调用 GET /api/interviews/{}", id);
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/answers")
    ApiResponse<?> answer(@PathVariable String id, @Valid @RequestBody AnswerRequest r) {
        log.info("接口调用 POST /api/interviews/{}/answers: 回答长度={}", id, r.answer() == null ? 0 : r.answer().length());
        return ApiResponse.ok(service.answer(id, r.answer));
    }

    @PostMapping("/{id}/skip")
    ApiResponse<?> skip(@PathVariable String id) {
        log.info("接口调用 POST /api/interviews/{}/skip", id);
        return ApiResponse.ok(service.skip(id));
    }

    @PostMapping("/{id}/complete")
    ApiResponse<?> complete(@PathVariable String id) {
        log.info("接口调用 POST /api/interviews/{}/complete", id);
        return ApiResponse.ok(service.complete(id));
    }

    @GetMapping("/{id}/report")
    ApiResponse<?> report(@PathVariable String id) {
        log.debug("接口调用 GET /api/interviews/{}/report", id);
        return ApiResponse.ok(service.report(id));
    }

    /**
     * 以 SSE 流式返回“面试官提问”。
     * 通过 SseEmitter 把模型增量转发给前端；emitter 完成/超时/出错时都会取消上游读取。
     */
    @GetMapping(value = "/{id}/question-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable String id) {
        log.info("接口调用 GET /api/interviews/{}/question-stream (SSE)", id);
        var current = service.get(id);
        if (current.question() == null) throw new BusinessException("已没有待回答问题");

        var emitter = new SseEmitter(90_000L);
        var cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));

        String prompt = "你是一名专业但友善的技术面试官。请自然地提出下面这道题，只输出问题本身，不添加答案或解释：\n" + current.question();

        CompletableFuture.runAsync(() -> {
            try {
                chat.stream(prompt, delta -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(delta));
                    } catch (IOException e) {
                        cancelled.set(true);
                        throw new StreamClosedException(e);
                    }
                }, cancelled::get);
                if (!cancelled.get()) {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                }
                log.debug("SSE 提问流结束: sessionId={}, cancelled={}", id, cancelled.get());
            } catch (Exception e) {
                if (!cancelled.get()) {
                    log.warn("SSE 提问流异常: sessionId={}, 原因={}", id, e.getMessage());
                    try {
                        emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    } catch (IOException ignored) {
                    }
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /** 标记前端连接已断开，用于中断读取模型流。 */
    private static final class StreamClosedException extends RuntimeException {
        StreamClosedException(Throwable cause) {
            super(cause);
        }
    }

    record CreateRequest(@Positive long questionSetId) { }

    record AnswerRequest(@NotBlank String answer) { }
}
