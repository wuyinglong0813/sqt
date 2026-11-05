package com.tradepass.module.identity.api.company.dto;

public record InviteResult(String code, String companyId, java.time.LocalDateTime expiresAt) {
    public InviteResult(String code, String companyId) { this(code, companyId, null); }
}
