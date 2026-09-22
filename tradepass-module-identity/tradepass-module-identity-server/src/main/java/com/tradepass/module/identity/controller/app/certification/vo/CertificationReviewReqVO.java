package com.tradepass.module.identity.controller.app.certification.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CertificationReviewReqVO(
        @NotBlank @Size(max = 64) String providerRequestId,
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED", message = "审核结果只能是 APPROVED 或 REJECTED") String decision,
        @Size(max = 512) String reason
) {
}
