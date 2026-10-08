package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 面试问答记录（每题/每次追问各一条）。 */
@TableName("interview_answer")
public class InterviewAnswerEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    public String sessionId;

    /** 被回答的题目文本。 */
    public String questionText;

    /** 题目类型：BASE / FOLLOW_UP。 */
    public String questionType;

    /** 若本题是追问，指向上一条回答 id（构成父子关系）。 */
    public Long parentAnswerId;

    public String answerText;

    /** 模型给出的评价 JSON。 */
    public String evaluationJson;

    public Double score;

    public LocalDateTime createdAt;
}
