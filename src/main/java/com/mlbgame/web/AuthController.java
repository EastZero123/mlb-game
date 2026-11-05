package com.mlbgame.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final AccountService accounts;

    public AuthController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/signup")
    public String signupForm() {
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@RequestParam String loginId, @RequestParam String password, @RequestParam String confirm,
                         Model model) {
        String error = accounts.signUp(loginId, password, confirm);
        if (error != null) {
            model.addAttribute("error", error);
            model.addAttribute("loginId", loginId);
            return "signup";
        }
        return "redirect:/login?signed";
    }
}
