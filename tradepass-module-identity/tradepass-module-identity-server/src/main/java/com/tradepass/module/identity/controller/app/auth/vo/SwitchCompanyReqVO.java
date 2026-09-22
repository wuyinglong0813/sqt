package com.tradepass.module.identity.controller.app.auth.vo;

import jakarta.validation.constraints.NotBlank;

public record SwitchCompanyReqVO(@NotBlank(message = "企业 ID 不能为空") String companyId) {
}
