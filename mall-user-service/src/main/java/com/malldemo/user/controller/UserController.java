package com.malldemo.user.controller;

import com.malldemo.common.ApiResponse;
import com.malldemo.user.dto.request.UserLoginRequest;
import com.malldemo.user.dto.request.UserRegisterRequest;
import com.malldemo.user.dto.response.UserLoginResponse;
import com.malldemo.user.dto.response.UserResponse;
import com.malldemo.user.entity.User;
import com.malldemo.user.security.JwtService;
import com.malldemo.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;
    private final JwtService jwtService;

    public UserController(
            UserService userService,
            JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    /**
     * 注册：只需要用户名、密码、可选昵称。
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> register(
            @Valid @RequestBody UserRegisterRequest request) {

        User user = userService.register(
                request.getUsername(),
                request.getPassword(),
                request.getNickname()
        );

        return ApiResponse.success(UserResponse.from(user));
    }

    /**
     * 登录：校验密码 → 签发 JWT。
     *
     * 响应里只给 token 和脱敏后的用户信息，绝不返回 passwordHash。
     */
    @PostMapping("/login")
    public ApiResponse<UserLoginResponse> login(
            @Valid @RequestBody UserLoginRequest request) {

        User user = userService.login(
                request.getUsername(),
                request.getPassword()
        );

        String token = jwtService.createAccessToken(user);

        UserLoginResponse response = new UserLoginResponse(
                token,
                "Bearer",
                jwtService.getExpirationSeconds(),
                UserResponse.from(user)
        );

        return ApiResponse.success(response);
    }
}
