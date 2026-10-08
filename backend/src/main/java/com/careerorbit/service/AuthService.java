package com.careerorbit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.careerorbit.entity.UserEntity;
import com.careerorbit.mapper.UserMapper;
import com.careerorbit.security.JwtService;
import com.careerorbit.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务：注册、登录，成功后签发 JWT。
 * 密码用 BCrypt 加密存储，登录时比对哈希；明文密码绝不落库。
 * 直接使用 Mapper + Entity（经典三层，无额外 domain 层）。
 */
@Service
public class AuthService {

    /** 日志。注意：绝不记录密码明文。 */
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 用户表 Mapper。 */
    private final UserMapper userMapper;

    /** 密码编码器（BCrypt）。 */
    private final PasswordEncoder encoder;

    /** JWT 签发工具。 */
    private final JwtService jwt;

    public AuthService(UserMapper userMapper, PasswordEncoder encoder, JwtService jwt) {
        this.userMapper = userMapper;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /**
     * 注册新用户（固定 USER 角色）。
     *
     * @param email    邮箱
     * @param password 明文密码（会被哈希）
     * @param name     昵称
     * @return 含 token 的认证结果
     */
    public AuthResult register(String email, String password, String name) {
        log.info("用户注册: email={}, name={}", email, name);
        if (findByEmail(email) != null) {
            log.warn("用户注册失败: 邮箱已注册 email={}", email);
            throw new BusinessException("该邮箱已注册");
        }
        var user = new UserEntity();
        user.email = email.toLowerCase();
        user.passwordHash = encoder.encode(password);
        user.displayName = name;
        user.role = "USER";
        user.createdAt = LocalDateTime.now();
        userMapper.insert(user);
        log.info("用户注册成功: id={}, email={}", user.id, user.email);
        return result(user);
    }

    /**
     * 登录：校验邮箱与密码。
     *
     * @param email    邮箱
     * @param password 明文密码
     * @return 含 token 的认证结果
     */
    public AuthResult login(String email, String password) {
        log.info("用户登录: email={}", email);
        var user = findByEmail(email);
        // 用户不存在或密码不匹配都返回同样的提示，避免暴露“邮箱是否存在”
        if (user == null || !encoder.matches(password, user.passwordHash)) {
            log.warn("登录失败: 邮箱或密码错误 email={}", email);
            throw new BusinessException("邮箱或密码错误");
        }
        log.info("登录成功: id={}, email={}", user.id, user.email);
        return result(user);
    }

    /** 按邮箱查用户（邮箱统一小写）。 */
    private UserEntity findByEmail(String email) {
        return userMapper.selectOne(new QueryWrapper<UserEntity>().eq("email", email == null ? null : email.toLowerCase()));
    }

    /** 组装认证结果：签发 token 并带上展示名与角色。 */
    private AuthResult result(UserEntity user) {
        return new AuthResult(jwt.issue(user), "Bearer", user.displayName, user.role);
    }

    /**
     * 认证结果。
     *
     * @param accessToken 访问令牌（前端存起来，后续放 Authorization 头）
     * @param tokenType   令牌类型，固定 Bearer
     * @param displayName 展示名
     * @param role        角色
     */
    public record AuthResult(String accessToken, String tokenType, String displayName, String role) { }
}
