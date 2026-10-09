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
        String perspective,
        BigDecimal salesAmount,
        BigDecimal returnAmount,
        int salesOrderCount
) {
    public ContractRespDTO(String id, String contractNo, String companyId, String counterpartyCompanyId,
                           String counterpartyName, String direction, String name, String templateName,
                           BigDecimal amount, String startDate, String endDate, String terms, String status,
                           Integer versionNo, String initiatedBy, String approvedBy, String approvedAt, String createdAt,
                           String supplierCompanyName, String buyerCompanyName, String viewerCompanyId,
                           String viewerCounterpartyCompanyId, String viewerCounterpartyName, String viewerDirection,
                           String perspective) {
        this(id, contractNo, companyId, counterpartyCompanyId, counterpartyName, direction, name, templateName,
                amount, startDate, endDate, terms, status, versionNo, initiatedBy, approvedBy, approvedAt, createdAt,
                supplierCompanyName, buyerCompanyName, viewerCompanyId, viewerCounterpartyCompanyId,
                viewerCounterpartyName, viewerDirection, perspective, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }
}
