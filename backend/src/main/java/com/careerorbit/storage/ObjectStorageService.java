package com.careerorbit.storage;

import com.careerorbit.common.BusinessException;
import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

/**
 * 对象存储（MinIO）封装：保存、读取、删除文件。
 * bucket 不存在时自动创建；文件 key 用 UUID + 净化后的文件名，避免重名与非法字符。
 */
@Service
public class ObjectStorageService {

    /** MinIO 客户端（连接池复用）。 */
    private final MinioClient minio;

    /** 使用的 bucket 名。 */
    private final String bucket;

    public ObjectStorageService(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.access-key}") String access,
            @Value("${app.storage.secret-key}") String secret,
            @Value("${app.storage.bucket}") String bucket) {
        this.bucket = bucket;
        this.minio = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
    }

    /** 保存文件，返回 key 与内部 url。prefix 用于按用途分目录（如 resumes/{userId}）。 */
    public StoredObject put(MultipartFile file, String prefix) {
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            String safe = (file.getOriginalFilename() == null ? "file" : file.getOriginalFilename())
                    .replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
            String key = prefix + "/" + UUID.randomUUID() + "-" + safe;
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            return new StoredObject(key, "minio://" + bucket + "/" + key);
        } catch (Exception e) {
            throw new BusinessException("MinIO 文件保存失败，请检查存储服务配置");
        }
    }

    /** 读取文件，返回流与元信息（供下载接口流式返回）。 */
    public StoredContent get(String key) {
        try {
            var stat = minio.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            InputStream stream = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
            return new StoredContent(stream, stat.contentType(), stat.size());
        } catch (Exception e) {
            throw new BusinessException("文件读取失败或已不存在");
        }
    }

    public void delete(String key) {
        if (key == null || key.isBlank()) return;
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new BusinessException("MinIO 文件删除失败");
        }
    }

    public record StoredObject(String key, String url) { }

    public record StoredContent(InputStream stream, String contentType, long size) { }
}
