package com.careerorbit.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.careerorbit.entity.UserEntity;
import com.careerorbit.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：每个请求解析 Authorization: Bearer &lt;token&gt;，
 * 校验通过后把认证信息放进 SecurityContext，后续鉴权与 CurrentUser 就能取到当前用户。
 * 角色始终以数据库为准，不信任 token 里的 role，避免改权限后旧 token 仍生效。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** JWT 校验工具。 */
    private final JwtService jwt;

    /** 用户表 Mapper（用邮箱查用户，取最新角色）。 */
    private final UserMapper userMapper;

    public JwtAuthenticationFilter(JwtService jwt, UserMapper userMapper) {
        this.jwt = jwt;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var email = jwt.subject(header.substring(7));
                var user = userMapper.selectOne(new QueryWrapper<UserEntity>().eq("email", email));
                if (user != null) {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(email, null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role))));
                }
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
