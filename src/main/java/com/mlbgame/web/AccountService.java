package com.mlbgame.web;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 계정: 가입·로그인 조회 (app_user). */
@Service
public class AccountService implements UserDetailsService {

    static final Pattern LOGIN_ID = Pattern.compile("[A-Za-z0-9_]{4,20}");
    static final int MIN_PASSWORD = 8;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public UserDetails loadUserByUsername(String loginId) {
        List<GameUser> found = jdbc.query("SELECT user_id, login_id, password_hash FROM app_user WHERE login_id = ?",
                (rs, n) -> new GameUser(rs.getInt(1), rs.getString(2), rs.getString(3)), loginId);
        if (found.isEmpty()) {
            throw new UsernameNotFoundException(loginId);
        }
        return found.getFirst();
    }

    /** @return 오류 메시지, 성공이면 null */
    public String signUp(String loginId, String password, String confirm) {
        if (loginId == null || !LOGIN_ID.matcher(loginId).matches()) {
            return "아이디는 영문·숫자·밑줄 4~20자입니다.";
        }
        if (password == null || password.length() < MIN_PASSWORD || password.length() > 72) {
            return "비밀번호는 8~72자입니다.";
        }
        if (!password.equals(confirm)) {
            return "비밀번호 확인이 일치하지 않습니다.";
        }
        try {
            jdbc.update("INSERT INTO app_user (login_id, password_hash) VALUES (?, ?)", loginId,
                    encoder.encode(password));
            return null;
        } catch (DuplicateKeyException e) {
            return "이미 사용 중인 아이디입니다.";
        }
    }
}
