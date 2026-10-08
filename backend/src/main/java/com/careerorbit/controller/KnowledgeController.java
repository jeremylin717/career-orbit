package com.careerorbit.controller;

import com.careerorbit.common.ApiResponse;
import com.careerorbit.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * RAG 知识库接口。
 * 检索与列表对所有登录用户开放；上传、删除、重建向量仅管理员可用。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    /** RAG 知识库业务服务。 */
    private final RagService rag;

    public KnowledgeController(RagService rag) {
        this.rag = rag;
    }

    @GetMapping("/search")
    ApiResponse<?> search(@RequestParam String query, @RequestParam(defaultValue = "5") int limit) {
        log.info("接口调用 GET /api/knowledge/search: 检索词长度={}, limit={}", query == null ? 0 : query.length(), limit);
        return ApiResponse.ok(rag.search(query, limit));
    }

    @GetMapping
    ApiResponse<?> list() {
        log.debug("接口调用 GET /api/knowledge");
        return ApiResponse.ok(rag.documents());
    }

    @GetMapping("/stats")
    ApiResponse<?> stats() {
        log.debug("接口调用 GET /api/knowledge/stats");
        return ApiResponse.ok(new RagService.Stats(rag.documents().size(), rag.chunkCount()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    ApiResponse<?> upload(@RequestPart MultipartFile file, @RequestParam String category, @RequestParam String technology, @RequestParam String difficulty) {
        log.info("接口调用 POST /api/knowledge: fileName={}, category={}", file == null ? null : file.getOriginalFilename(), category);
        return ApiResponse.ok(rag.upload(file, category, technology, difficulty));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    ApiResponse<?> delete(@PathVariable long id) {
        log.info("接口调用 DELETE /api/knowledge/{}", id);
        rag.delete(id);
        return ApiResponse.ok(null);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/reindex")
    ApiResponse<?> reindex(@PathVariable long id) {
        log.info("接口调用 POST /api/knowledge/{}/reindex", id);
        return ApiResponse.ok(rag.reindex(id));
    }
}
