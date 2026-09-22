package com.tradepass.module.identity.controller.app.company.vo;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ApproveReqVO(String roleCode, List<String> customPermissions, List<@NotBlank String> roleCodes) {
    public ApproveReqVO(String roleCode, List<String> customPermissions) {
        this(roleCode, customPermissions, null);
    }
}
