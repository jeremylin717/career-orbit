package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/**
 * 提示词模板。
 * 注意：此表由管理后台维护，但当前业务代码使用 PromptTemplates 中的硬编码提示词，
 * 尚未从此表读取（即“提示词管理”暂不生效，属已知缺口）。
 */
@TableName("prompt_template")
public class PromptTemplateEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    /** 唯一键，如 interview.followup。 */
    public String promptKey;

    public String name;
    public String content;
    public Boolean enabled;

    public LocalDateTime updatedAt;
}
