package com.tradepass.module.identity.controller.app.company.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RoleReqVO(@NotBlank String companyId, @NotBlank String name, @NotNull List<String> permissions) {
}
