package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** AI 调用日志。注意：只记模型与耗时，绝不记录简历或回答原文。 */
@TableName("ai_call_log")
public class AiCallLogEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    /** 一次调用的追踪 id。 */
    public String traceId;

    /** 网关来源：CHAT / EMBEDDING / VISION。 */
    public String provider;

    public String model;

    /** 操作名，如 chat、chat_stream、embedding、vision_extract。 */
    public String operation;

    public Integer inputTokens;
    public Integer outputTokens;

    /** 调用耗时（毫秒）。 */
    public Long latencyMs;

    public Boolean success;

    /** 失败时的错误摘要。 */
    public String errorCode;

    public LocalDateTime createdAt;
}
