package com.tradepass.module.identity.api.fadada.dto;

public record PersonalIdentityRespDTO(
        boolean providerEnabled,
        String status,
        String statusText,
        String bindingStatus,
        String identStatus,
        String identProcessStatus,
        String verifiedName,
        String identMethod,
        String failureReason,
        String submittedAt,
        String verifiedAt,
        String lastSyncAt
) {
}
