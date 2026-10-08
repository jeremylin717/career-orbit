package com.careerorbit.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import com.careerorbit.common.ApiResponse;
import com.careerorbit.service.AuthService;
import com.careerorbit.security.CurrentUser;

/** 认证接口：注册、登录、当前用户信息。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 日志。注意：绝不记录密码。 */
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** 认证业务服务。 */
    private final AuthService auth;

    /** 当前登录用户。 */
    private final CurrentUser current;

    public AuthController(AuthService auth, CurrentUser current) {
        this.auth = auth;
        this.current = current;
    }

    @PostMapping("/register")
    ApiResponse<AuthService.AuthResult> register(@Valid @RequestBody RegisterRequest r) {
        log.info("接口调用 POST /api/auth/register: email={}", r.email());
        return ApiResponse.ok(auth.register(r.email(), r.password(), r.displayName()));
    }

    @PostMapping("/login")
    ApiResponse<AuthService.AuthResult> login(@Valid @RequestBody LoginRequest r) {
        log.info("接口调用 POST /api/auth/login: email={}", r.email());
        return ApiResponse.ok(auth.login(r.email(), r.password()));
    }

    /** 返回当前登录用户资料（角色实时取自数据库）。 */
    @GetMapping("/me")
    ApiResponse<?> me() {
        log.debug("接口调用 GET /api/auth/me");
        var u = current.require();
        return ApiResponse.ok(new Profile(u.id, u.email, u.displayName, u.role, u.targetRole));
    }

    record RegisterRequest(@Email String email, @Size(min = 8, max = 72) String password, @NotBlank String displayName) { }

    record LoginRequest(@Email String email, @NotBlank String password) { }

    record Profile(long id, String email, String displayName, String role, String targetRole) { }
}
