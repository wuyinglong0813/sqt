package com.tradepass.module.identity.controller.app.company.vo;

import jakarta.validation.constraints.NotBlank;

public record InviteReqVO(@NotBlank String companyId, String relationRole, String targetPhone) {
    public InviteReqVO(String companyId, String relationRole) {
        this(companyId, relationRole, null);
    }
}
