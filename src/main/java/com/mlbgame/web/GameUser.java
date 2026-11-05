package com.mlbgame.web;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** 로그인 사용자 (세션에 저장). 화면마다 app_user 를 다시 조회하지 않도록 user_id 를 함께 둔다. */
public class GameUser extends User {

    private final int userId;

    public GameUser(int userId, String loginId, String passwordHash) {
        super(loginId, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.userId = userId;
    }

    public int userId() {
        return userId;
    }
}
