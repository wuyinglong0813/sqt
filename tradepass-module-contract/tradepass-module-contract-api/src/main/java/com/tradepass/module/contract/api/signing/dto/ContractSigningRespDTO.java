package com.tradepass.module.contract.api.signing.dto;

public record ContractSigningRespDTO(
        String contractId,
        String status,
        String statusText,
        String initiatorSignStatus,
        String counterpartySignStatus,
        boolean canSign,
        boolean canCancel,
        boolean canAbolish,
        boolean abolishApproved,
        boolean signedFileArchived,
        String lastError
) {}
