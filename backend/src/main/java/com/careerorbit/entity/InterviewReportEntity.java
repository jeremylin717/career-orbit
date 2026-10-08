package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 面试报告。 */
@TableName("interview_report")
public class InterviewReportEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    /** 与面试会话一一对应。 */
    public String sessionId;

    public Long userId;

    /** 综合得分（逐题得分均值）。 */
    public Double overallScore;

    /** 报告完整 JSON。 */
    public String reportJson;

    public LocalDateTime createdAt;
}
