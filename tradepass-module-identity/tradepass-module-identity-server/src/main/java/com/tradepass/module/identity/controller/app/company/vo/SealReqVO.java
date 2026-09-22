package com.tradepass.module.identity.controller.app.company.vo;

import jakarta.validation.constraints.NotBlank;

public record SealReqVO(@NotBlank String companyId, @NotBlank String fileUrl, @NotBlank String usage) {
}
