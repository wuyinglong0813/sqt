package com.tradepass.module.identity.api.company.dto;

public record JoinResult(String status, String message, String applicationId, String companyId, String companyName) {
    public JoinResult(String status, String message) { this(status, message, null, null, null); }
}
