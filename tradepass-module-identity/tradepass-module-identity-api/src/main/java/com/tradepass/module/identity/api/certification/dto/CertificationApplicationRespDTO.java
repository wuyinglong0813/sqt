package com.tradepass.module.identity.api.certification.dto;

public record CertificationApplicationRespDTO(
        String id,
        String companyId,
        String companyName,
        String providerRequestId,
        String status,
        String reviewReason,
        String submittedAt,
        String reviewedAt
) {
}
