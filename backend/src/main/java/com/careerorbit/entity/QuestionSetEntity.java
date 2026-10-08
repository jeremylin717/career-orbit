package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 生成的题目集。 */
@TableName("question_set")
public class QuestionSetEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    public Long userId;
    public Long resumeId;

    /** 展示标题，如 “高级 Java 工程师 · 进阶”。 */
    public String title;

    public String jobTitle;
    public String jobDescription;

    /** 生成时的配置 JSON（用于追溯）。 */
    public String configJson;

    /** 生成的题目 JSON 数组。 */
    public String questionsJson;

    public LocalDateTime createdAt;
}
