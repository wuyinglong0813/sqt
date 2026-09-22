package com.tradepass.module.identity.controller.app.company.vo;

import jakarta.validation.constraints.NotBlank;

public record JoinReqVO(@NotBlank String code) {
}
