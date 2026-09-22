package com.tradepass.module.identity.controller.app.company.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanySubmitReqVO(
        String id,
        @NotBlank String name,
        @NotBlank String creditCode,
        @NotBlank String legalPersonName,
        @Size(max = 256) String registeredAddress,
        @Size(max = 32) String contactPhone,
        @Size(max = 128) String bankName,
        @Size(max = 64) String bankAccount
) {
    public CompanySubmitReqVO(String id, String name, String creditCode, String legalPersonName) {
        this(id, name, creditCode, legalPersonName, null, null, null, null);
    }
}
