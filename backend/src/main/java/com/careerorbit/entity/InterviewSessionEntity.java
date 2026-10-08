package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 模拟面试会话表。 */
@TableName("interview_session")
public class InterviewSessionEntity {

    /** 会话 id：用 UUID 字符串作为主键（应用侧生成）。 */
    @TableId(type = IdType.INPUT)
    public String id;

    public Long userId;
    public String targetRole;

    /** 状态：IN_PROGRESS / COMPLETED。 */
    public String status;

    public Long questionSetId;

    /** 当前进行到第几道主问题（下标从 0 开始）。 */
    public Integer currentQuestion;

    /** 当前待回答的题目文本（可能是主问题，也可能是追问）。 */
    public String currentQuestionText;

    /** 当前题目类型：BASE（主问题）/ FOLLOW_UP（追问）。 */
    public String questionType;

    /** 若当前是追问，指向触发它的上一条回答 id。 */
    public Long parentAnswerId;

    /** 当前主问题已追问次数（上限 2）。 */
    public Integer followUpCount;

    /** 最近一次评价与引用的快照 JSON，便于刷新恢复。 */
    public String snapshotJson;

    public LocalDateTime startedAt;
    public LocalDateTime endedAt;
}
