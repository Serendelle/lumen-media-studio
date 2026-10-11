package com.houqing.tools.video_compressor_backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "^[A-Za-z0-9_\\-\\u4e00-\\u9fa5]+$", message = "只能包含字母、数字、下划线、短横线或中文") String username,
            @NotBlank @Size(min = 8, max = 72, message = "密码长度需为 8-72 位") String password,
            @Size(max = 32) String displayName) {}

    public record LoginRequest(@NotBlank @Size(max = 32) String username, @NotBlank @Size(max = 72) String password) {}

    @PostMapping("/register")
    public AuthService.AuthResult register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req.username(), req.password(), req.displayName());
    }

    @PostMapping("/login")
    public AuthService.AuthResult login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req.username(), req.password(), http.getRemoteAddr());
    }

    @GetMapping("/me")
    public AuthService.UserView me() {
        return auth.me(CurrentUser.id());
    }
}
