package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.ModelKeys;
import org.example.finance.constant.Routes;
import org.example.finance.constant.SessionKeys;
import org.example.finance.constant.Views;
import org.example.finance.model.User;
import org.example.finance.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final UserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/login")
    public String loginPage() {
        return Views.LOGIN;
    }

    @GetMapping("/register")
    public String registerPage() {
        return Views.REGISTER;
    }

    @PostMapping("/register")
    public String register(@RequestParam String name,
                           @RequestParam String email,
                           @RequestParam String password,
                           HttpSession session,
                           Model model) {
        if (userRepository.findByEmail(email) != null) {
            model.addAttribute(ModelKeys.ERROR, "该邮箱已被注册，请直接登录");
            return Views.REGISTER;
        }
        if (password.length() < 6) {
            model.addAttribute(ModelKeys.ERROR, "密码长度不能少于6位");
            return Views.REGISTER;
        }

        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        User saved = userRepository.save(user);

        // 注册成功后直接写入 session，跳转到风险画像填写页
        session.setAttribute(SessionKeys.USER, saved);
        return "redirect:/profile/setup";
    }

    @PostMapping("/login")
    public String login(@RequestParam String email,
                        @RequestParam String password,
                        HttpSession session, Model model) {
        User user = userRepository.findByEmail(email);

        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            model.addAttribute(ModelKeys.ERROR, "邮箱或密码错误");
            return Views.LOGIN;
        }

        // 登录成功：写入 session，供拦截器和控制层读取
        session.setAttribute(SessionKeys.USER, user);
        return Routes.redirectTo(Routes.DASHBOARD);
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();   // 销毁整个 session（比 setComplete() 更彻底）
        return Routes.redirectTo(Routes.LOGIN);
    }
}
