package com.careerorbit.controller;

import com.careerorbit.common.ApiResponse;
import com.careerorbit.service.QuestionGenerationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 面试题集接口：生成、列表、详情、删除。 */
@RestController
@RequestMapping("/api/question-sets")
public class QuestionController {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);

    /** 题目生成业务服务。 */
    private final QuestionGenerationService service;

    public QuestionController(QuestionGenerationService service) {
        this.service = service;
    }

    @PostMapping
    ApiResponse<?> generate(@Valid @RequestBody GenerateRequest r) {
        log.info("接口调用 POST /api/question-sets: resumeId={}, jobTitle={}, count={}", r.resumeId, r.jobTitle, r.count);
        return ApiResponse.ok(service.generate(new QuestionGenerationService.GenerateCommand(
                r.resumeId, r.jobTitle, r.jobDescription, r.technology, r.difficulty, r.count, r.types)));
    }

    @GetMapping
    ApiResponse<?> list() {
        log.debug("接口调用 GET /api/question-sets");
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    ApiResponse<?> get(@PathVariable long id) {
        log.debug("接口调用 GET /api/question-sets/{}", id);
        return ApiResponse.ok(service.get(id));
    }

    @DeleteMapping("/{id}")
    ApiResponse<?> delete(@PathVariable long id) {
        log.info("接口调用 DELETE /api/question-sets/{}", id);
        service.delete(id);
        return ApiResponse.ok(null);
    }

    record GenerateRequest(
            @Positive long resumeId,
            @NotBlank String jobTitle,
            @NotBlank String jobDescription,
            @NotBlank String technology,
            @NotBlank String difficulty,
            @Min(1) @Max(30) int count,
            @NotEmpty List<String> types) { }
}
