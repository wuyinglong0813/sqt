package com.tradepass.module.identity.controller.app.counterparty.vo;

import jakarta.validation.constraints.NotBlank;

public record AddCounterpartyReqVO(@NotBlank String counterpartyName) {
}
