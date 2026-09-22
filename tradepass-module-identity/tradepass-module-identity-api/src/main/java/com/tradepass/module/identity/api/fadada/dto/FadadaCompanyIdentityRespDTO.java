package com.tradepass.module.identity.api.fadada.dto;

import java.util.List;

public record FadadaCompanyIdentityRespDTO(
        boolean enabled,
        String companyId,
        String status,
        String statusText,
        String verifiedName,
        String verifiedCreditCode,
        String failureReason,
        int enabledSealCount,
        List<SealPayload> seals,
        String lastSyncAt,
        String sealSyncWarning
) {
    public FadadaCompanyIdentityRespDTO(boolean enabled, String companyId, String status, String statusText,
                                       String verifiedName, String verifiedCreditCode, String failureReason,
                                       int enabledSealCount, List<SealPayload> seals, String lastSyncAt) {
        this(enabled, companyId, status, statusText, verifiedName, verifiedCreditCode, failureReason,
                enabledSealCount, seals, lastSyncAt, null);
    }
    public record SealPayload(String sealId, String sealName, String categoryType, String status) {}
}
