package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 简历诊断结果。 */
@TableName("resume_diagnosis")
public class ResumeDiagnosisEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    public Long resumeId;

    /** 模型返回的完整诊断 JSON（含 issues 列表）。 */
    public String resultJson;

    /** 已采纳的建议 id 列表（JSON 数组字符串）。 */
    public String acceptedItemsJson;

    public LocalDateTime createdAt;
}
