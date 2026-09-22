package com.tradepass.module.identity.controller.app.auth;

import com.tradepass.framework.common.pojo.TradePassDtos;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.pojo.TradePassDtos.DevUser;
import com.tradepass.framework.common.pojo.TradePassDtos.LoginSession;
import com.tradepass.framework.common.pojo.TradePassDtos.MePayload;
import com.tradepass.framework.common.pojo.TradePassDtos.UserProfile;
import com.tradepass.module.identity.controller.app.auth.vo.BindCompanyReqVO;
import com.tradepass.module.identity.controller.app.auth.vo.BindPhoneReqVO;
import com.tradepass.module.identity.controller.app.auth.vo.SwitchCompanyReqVO;
import com.tradepass.module.identity.controller.app.auth.vo.SwitchUserReqVO;
import com.tradepass.module.identity.controller.app.auth.vo.WechatLoginReqVO;
import com.tradepass.module.trade.api.document.dto.TodoItem;
import com.tradepass.module.identity.service.auth.AuthService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/wechat-login")
    public ApiResponse<LoginSession> wechatLogin(@Valid @RequestBody WechatLoginReqVO request,
                                                  HttpServletRequest servletRequest) {
        return ApiResponse.ok(authService.wechatLogin(request,
                servletRequest.getHeader("x-wx-openid")));
    }

    @PostMapping("/auth/bind-phone")
    public ApiResponse<UserProfile> bindPhone(@Valid @RequestBody BindPhoneReqVO request) {
        return ApiResponse.ok(authService.bindPhone(request));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(request.getHeader("Authorization"));
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<MePayload> me() {
        return ApiResponse.ok(authService.me());
    }

    @GetMapping("/permissions")
    public ApiResponse<List<Map<String, Object>>> permissions() {
        return ApiResponse.ok(authService.permissions());
    }

    @GetMapping("/me/todos")
    public ApiResponse<List<TodoItem>> myTodos() {
        return ApiResponse.ok(authService.myTodos());
    }

    @PostMapping("/me/switch-company")
    public ApiResponse<MePayload> switchCompany(@Valid @RequestBody SwitchCompanyReqVO request) {
        return ApiResponse.ok(authService.switchCompany(request));
    }

    @PostMapping("/me/company")
    public ApiResponse<MePayload> bindCompany(@Valid @RequestBody BindCompanyReqVO request) {
        return ApiResponse.ok(authService.bindCompany(request));
    }

    @GetMapping("/dev/users")
    public ApiResponse<List<DevUser>> listDevUsers() {
        return ApiResponse.ok(authService.listDevUsers());
    }

    @PostMapping("/dev/switch-user")
    public ApiResponse<LoginSession> switchUser(@Valid @RequestBody SwitchUserReqVO request) {
        return ApiResponse.ok(authService.switchUser(request));
    }
}
