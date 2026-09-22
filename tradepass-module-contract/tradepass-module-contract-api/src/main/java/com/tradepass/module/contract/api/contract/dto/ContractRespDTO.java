package com.tradepass.module.contract.api.contract.dto;

import java.math.BigDecimal;

public record ContractRespDTO(
        String id,
        String contractNo,
        String companyId,
        String counterpartyCompanyId,
        String counterpartyName,
        String direction,
        String name,
        String templateName,
        BigDecimal amount,
        String startDate,
        String endDate,
        String terms,
        String status,
        Integer versionNo,
        String initiatedBy,
        String approvedBy,
        String approvedAt,
        String createdAt,
        String supplierCompanyName,
        String buyerCompanyName,
        String viewerCompanyId,
        String viewerCounterpartyCompanyId,
        String viewerCounterpartyName,
        String viewerDirection,
        String perspective
) {
}
