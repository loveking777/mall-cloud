package com.malldemo.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 mall_db.mall_user。
 *
 * 注意：字段名 passwordHash 对应列 password_hash，
 * 由 mybatis-plus.configuration.map-underscore-to-camel-case 自动映射。
 * 数据库里只存 BCrypt 哈希，永远不存明文密码。
 */
@TableName("mall_user")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String nickname;

    /**
     * 1 = 正常，0 = 禁用
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
