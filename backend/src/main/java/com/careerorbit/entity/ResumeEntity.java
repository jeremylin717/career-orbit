package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 简历表。同一份简历的多个版本通过 parentId 串成版本树。 */
@TableName("resume")
public class ResumeEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    public Long userId;
    public String fileName;

    /** 对象存储 key（原文件）。 */
    public String objectKey;
    public String fileUrl;

    /** Tika 抽取的原始文本。 */
    public String rawText;

    /** 模型抽取/修改后的结构化 JSON。 */
    public String parsedJson;

    /** 父版本 id（首版为空）。 */
    public Long parentId;

    /** 该版本来自哪次诊断。 */
    public Long sourceDiagnosisId;

    /** 该版本采纳了哪条建议。 */
    public String sourceSuggestionId;

    public Integer versionNo;

    public LocalDateTime createdAt;
}
