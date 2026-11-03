package com.malldemo.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.malldemo.common.BusinessException;
import com.malldemo.user.entity.User;
import com.malldemo.user.mapper.UserMapper;
import com.malldemo.user.service.UserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class UserServiceImpl
        extends ServiceImpl<UserMapper, User>
        implements UserService {

    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public User register(
            String username,
            String rawPassword,
            String nickname
    ) {
        String normalizedUsername = username.trim();

        // BCrypt 的密码输入最多支持 72 字节（不是 72 个字符）
        int passwordBytes = rawPassword
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (passwordBytes > 72) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "密码的UTF-8编码长度不能超过72字节"
            );
        }

        // 先查一次，给出友好提示
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, normalizedUsername);

        if (this.count(wrapper) > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "用户名已存在"
            );
        }

        User user = new User();
        user.setUsername(normalizedUsername);

        // 只保存哈希，不保存原始密码
        user.setPasswordHash(
                passwordEncoder.encode(rawPassword)
        );

        if (nickname == null || nickname.isBlank()) {
            user.setNickname(normalizedUsername);
        } else {
            user.setNickname(nickname.trim());
        }

        user.setStatus(1);

        try {
            if (!this.save(user)) {
                throw new BusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "用户注册失败"
                );
            }
        } catch (DuplicateKeyException ex) {
            // 两个并发注册请求可能同时通过上面的 count 检查，
            // 最终由 uk_mall_user_username 唯一索引拦住一个。
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "用户名已存在"
            );
        }

        return user;
    }

    @Override
    public User login(
            String username,
            String rawPassword
    ) {
        // 超长密码直接按"用户名或密码错误"处理，
        // 避免把"密码格式问题"暴露成可探测的信息
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "用户名或密码错误"
            );
        }

        String normalizedUsername = username.trim();

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, normalizedUsername);

        User user = this.getOne(wrapper);

        // 用户不存在和密码错误返回同一句话，
        // 防止攻击者通过提示判断用户名是否存在
        if (user == null
                || !passwordEncoder.matches(
                rawPassword,
                user.getPasswordHash()
        )) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "用户名或密码错误"
            );
        }

        // 密码正确后，才判断账号是否被禁用
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "账户已禁用"
            );
        }

        return user;
    }
}
