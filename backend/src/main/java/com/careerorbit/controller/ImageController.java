package com.careerorbit.controller;

import com.careerorbit.ai.VisionGateway;
import com.careerorbit.common.ApiResponse;
import com.careerorbit.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 图片识别接口：识别图片中的岗位 JD 文字。 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    /** 视觉网关（识别图片中的文字）。 */
    private final VisionGateway vision;

    public ImageController(VisionGateway vision) {
        this.vision = vision;
    }

    /** 识别上传图片中的岗位 JD 文字，返回 { text: "..." }。 */
    @PostMapping("/extract-jd")
    ApiResponse<?> extractJd(@RequestPart("file") MultipartFile file) {
        log.info("接口调用 POST /api/images/extract-jd: contentType={}, size={}",
                file == null ? null : file.getContentType(), file == null ? 0 : file.getSize());
        if (file == null || file.isEmpty()) throw new BusinessException("请选择要识别的图片");
        String ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/")) throw new BusinessException("仅支持图片文件（PNG、JPG 等）");
        if (file.getSize() > 10 * 1024 * 1024) throw new BusinessException("图片不能超过 10MB");
        try {
            String text = vision.extractText(file.getBytes(), ct);
            log.info("图片识别完成: 识别文字长度={}", text == null ? 0 : text.length());
            return ApiResponse.ok(Map.of("text", text));
        } catch (BusinessException e) {
            log.warn("图片识别失败: 原因={}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("图片识别失败", e);
            throw new BusinessException("图片识别失败：" + e.getMessage());
        }
    }
}
