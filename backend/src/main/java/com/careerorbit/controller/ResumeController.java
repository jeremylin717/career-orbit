package com.careerorbit.controller;

import com.careerorbit.common.ApiResponse;
import com.careerorbit.service.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 简历相关接口。
 * 路由 /api/resumes，涵盖上传、列表、下载、诊断、版本与采纳/撤销。
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    /** 简历业务服务。 */
    private final ResumeService service;

    public ResumeController(ResumeService service) {
        this.service = service;
    }

    /** 上传简历文件。 */
    @PostMapping
    ApiResponse<?> upload(@RequestPart("file") MultipartFile file) {
        log.info("接口调用 POST /api/resumes: fileName={}", file == null ? null : file.getOriginalFilename());
        return ApiResponse.ok(service.upload(file));
    }

    @GetMapping
    ApiResponse<?> list() {
        log.debug("接口调用 GET /api/resumes");
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    ApiResponse<?> get(@PathVariable long id) {
        log.debug("接口调用 GET /api/resumes/{}", id);
        return ApiResponse.ok(service.get(id));
    }

    /** 鉴权下载原始文件：以流式响应返回，附件名做 UTF-8 编码避免中文乱码。 */
    @GetMapping("/{id}/download")
    ResponseEntity<StreamingResponseBody> download(@PathVariable long id) {
        log.info("接口调用 GET /api/resumes/{}/download", id);
        var download = service.download(id);
        StreamingResponseBody body = out -> {
            try (var input = download.content().stream()) {
                input.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        download.content().contentType() == null ? "application/octet-stream" : download.content().contentType()))
                .contentLength(download.content().size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    @PostMapping("/{id}/diagnose")
    ApiResponse<?> diagnose(@PathVariable long id) {
        log.info("接口调用 POST /api/resumes/{}/diagnose", id);
        return ApiResponse.ok(service.diagnose(id));
    }

    @GetMapping("/{id}/diagnosis")
    ApiResponse<?> diagnosis(@PathVariable long id) {
        log.debug("接口调用 GET /api/resumes/{}/diagnosis", id);
        return ApiResponse.ok(service.diagnosis(id));
    }

    @PostMapping("/{id}/versions")
    ApiResponse<?> version(@PathVariable long id, @RequestBody JsonNode parsed) {
        log.info("接口调用 POST /api/resumes/{}/versions", id);
        return ApiResponse.ok(service.saveVersion(id, parsed));
    }

    @GetMapping("/{id}/versions")
    ApiResponse<?> versions(@PathVariable long id) {
        log.debug("接口调用 GET /api/resumes/{}/versions", id);
        return ApiResponse.ok(service.versions(id));
    }

    @GetMapping("/versions/diff")
    ApiResponse<?> diff(@RequestParam long beforeId, @RequestParam long afterId) {
        log.info("接口调用 GET /api/resumes/versions/diff: beforeId={}, afterId={}", beforeId, afterId);
        return ApiResponse.ok(service.diff(beforeId, afterId));
    }

    @PostMapping("/diagnoses/{diagnosisId}/suggestions/{suggestionId}/accept")
    ApiResponse<?> accept(@PathVariable long diagnosisId, @PathVariable String suggestionId, @RequestBody JsonNode parsed) {
        log.info("接口调用 采纳建议: diagnosisId={}, suggestionId={}", diagnosisId, suggestionId);
        return ApiResponse.ok(service.accept(diagnosisId, suggestionId, parsed));
    }

    @DeleteMapping("/diagnoses/{diagnosisId}/suggestions/{suggestionId}/acceptance")
    ApiResponse<?> undo(@PathVariable long diagnosisId, @PathVariable String suggestionId) {
        log.info("接口调用 撤销采纳: diagnosisId={}, suggestionId={}", diagnosisId, suggestionId);
        return ApiResponse.ok(service.undo(diagnosisId, suggestionId));
    }
}

