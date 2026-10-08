package com.careerorbit.config;

import com.careerorbit.entity.UserEntity;
import com.careerorbit.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动时按 app.admin.email 保证存在一个管理员账号：
 * <ul>
 *   <li>该邮箱不存在：创建 ADMIN 账号（密码取 app.admin.password）</li>
 *   <li>该邮箱已存在但不是 ADMIN（例如先前被注册成了普通用户）：自动提升为 ADMIN</li>
 * </ul>
 * 这样无论用 IDEA 还是脚本启动，配置的管理员邮箱最终都是管理员角色。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    /** 用户表 Mapper。 */
    private final UserMapper users;

    /** 密码编码器。 */
    private final PasswordEncoder encoder;

    /** 配置的管理员邮箱。 */
    private final String email;

    /** 配置的管理员初始密码。 */
    private final String password;

    public AdminBootstrap(UserMapper users, PasswordEncoder encoder, @Value("${app.admin.email}") String email, @Value("${app.admin.password}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 未配置邮箱或密码就不处理
        if (email == null || email.isBlank() || password == null || password.isBlank()) return;
        var existing = users.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserEntity>().eq("email", email));
        if (existing != null) {
            // 已存在但角色不是 ADMIN，则提升角色（不改密码）
            if (!"ADMIN".equalsIgnoreCase(existing.role)) {
                existing.role = "ADMIN";
                users.updateById(existing);
            }
            return;
        }
        // 不存在则新建管理员
        var u = new UserEntity();
        u.email = email;
        u.passwordHash = encoder.encode(password);
        u.displayName = "系统管理员";
        u.role = "ADMIN";
        u.createdAt = LocalDateTime.now();
        users.insert(u);
    }
}
