package com.careerorbit.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** 用户表。 */
@TableName("app_user")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    public Long id;

    public String email;

    /** BCrypt 哈希后的密码，绝不存明文。 */
    public String passwordHash;

    public String displayName;

    /** 角色：USER / ADMIN。 */
    public String role;

    /** 求职方向（预留，当前无编辑入口）。 */
    public String targetRole;

    @TableField(fill = FieldFill.INSERT)
    public LocalDateTime createdAt;
}
