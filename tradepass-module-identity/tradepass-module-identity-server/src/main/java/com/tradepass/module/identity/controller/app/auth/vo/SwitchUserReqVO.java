package com.tradepass.module.identity.controller.app.auth.vo;

import jakarta.validation.constraints.NotBlank;

public record SwitchUserReqVO(@NotBlank(message = "用户 ID 不能为空") String userId) {
}
