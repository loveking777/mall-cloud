package com.malldemo.user.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.malldemo.user.entity.User;

public interface UserService extends IService<User> {

    /**
     * 注册。
     *
     * @return 注册成功后的用户（含数据库生成的 id）
     */
    User register(String username, String rawPassword, String nickname);

    /**
     * 登录校验。
     *
     * @return 校验通过的用户
     */
    User login(String username, String rawPassword);
}
