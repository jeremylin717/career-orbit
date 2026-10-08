package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 知识库文档。 */
@TableName("knowledge_document")
public class KnowledgeDocumentEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    public String title;

    /** 分类：面试题 / 参考答案 / 评分标准 / 技术文档。 */
    public String category;

    public String difficulty;

    /** 对象存储中的内部地址。 */
    public String sourceUri;

    /** 对象存储 key，用于删除原文件。 */
    public String objectKey;

    /** 处理状态：PROCESSING / READY / FAILED。 */
    public String status;

    /** 失败原因。 */
    public String errorReason;

    public LocalDateTime createdAt;
}
