package com.malldemo.user.dto.response;

import com.malldemo.user.entity.User;
import lombok.Data;

/**
 * 对外暴露的用户信息。
 *
 * 故意不包含 passwordHash，
 * 避免实体对象被直接序列化时把哈希泄露出去。
 */
@Data
public class UserResponse {

    private Long id;
    private String username;
    private String nickname;
    private Integer status;

    public UserResponse(
            Long id,
            String username,
            String nickname,
            Integer status
    ) {
        this.id = id;
        this.username = username;
        this.nickname = nickname;
        this.status = status;
    }

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getStatus()
        );
    }
}
