package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 知识库分块（文档切分后的每一块，含其向量在 ES 中的 id）。 */
@TableName("knowledge_chunk")
public class KnowledgeChunkEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    public Long documentId;

    /** 块在同一文档内的序号。 */
    public Integer chunkIndex;

    /** 分块正文。 */
    public String content;

    public String category;
    public String technology;
    public String difficulty;

    /** 来源（文档内部地址）。 */
    public String source;

    /** 该块在 Elasticsearch 中的文档 id。 */
    public String vectorId;

    public LocalDateTime createdAt;
}
