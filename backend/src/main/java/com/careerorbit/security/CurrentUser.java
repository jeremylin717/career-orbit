package com.careerorbit.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.careerorbit.entity.UserEntity;
import com.careerorbit.mapper.UserMapper;
import com.careerorbit.common.BusinessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 取当前登录用户。
 * 依据 SecurityContext 里的认证名（邮箱）实时查库，保证角色等信息始终最新。
 */
@Component
public class CurrentUser {

    /** 用户表 Mapper（按认证名=邮箱 查用户）。 */
    private final UserMapper users;

    public CurrentUser(UserMapper users) {
        this.users = users;
    }

    public UserEntity require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new BusinessException("请先登录");
        var user = users.selectOne(new QueryWrapper<UserEntity>().eq("email", auth.getName()));
        if (user == null) throw new BusinessException("用户不存在");
        return user;
    }
}
