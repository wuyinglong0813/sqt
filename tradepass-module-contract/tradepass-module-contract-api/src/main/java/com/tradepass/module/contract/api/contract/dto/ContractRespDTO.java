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
        int salesOrderCount,
        String statusText,
        boolean abolishPending,
        String signingStatus
) {
    public ContractRespDTO(String id, String contractNo, String companyId, String counterpartyCompanyId,
                           String counterpartyName, String direction, String name, String templateName,
                           BigDecimal amount, String startDate, String endDate, String terms, String status,
                           Integer versionNo, String initiatedBy, String approvedBy, String approvedAt, String createdAt,
                           String supplierCompanyName, String buyerCompanyName, String viewerCompanyId,
                           String viewerCounterpartyCompanyId, String viewerCounterpartyName, String viewerDirection,
                           String perspective, BigDecimal salesAmount, BigDecimal returnAmount, int salesOrderCount,
                           String statusText, boolean abolishPending) {
        this(id, contractNo, companyId, counterpartyCompanyId, counterpartyName, direction, name, templateName,
                amount, startDate, endDate, terms, status, versionNo, initiatedBy, approvedBy, approvedAt, createdAt,
                supplierCompanyName, buyerCompanyName, viewerCompanyId, viewerCounterpartyCompanyId,
                viewerCounterpartyName, viewerDirection, perspective, salesAmount, returnAmount, salesOrderCount,
                statusText, abolishPending, null);
    }
    public ContractRespDTO(String id, String contractNo, String companyId, String counterpartyCompanyId,
                           String counterpartyName, String direction, String name, String templateName,
                           BigDecimal amount, String startDate, String endDate, String terms, String status,
                           Integer versionNo, String initiatedBy, String approvedBy, String approvedAt, String createdAt,
                           String supplierCompanyName, String buyerCompanyName, String viewerCompanyId,
                           String viewerCounterpartyCompanyId, String viewerCounterpartyName, String viewerDirection,
                           String perspective, BigDecimal salesAmount, BigDecimal returnAmount, int salesOrderCount,
                           String statusText) {
        this(id, contractNo, companyId, counterpartyCompanyId, counterpartyName, direction, name, templateName,
                amount, startDate, endDate, terms, status, versionNo, initiatedBy, approvedBy, approvedAt, createdAt,
                supplierCompanyName, buyerCompanyName, viewerCompanyId, viewerCounterpartyCompanyId,
                viewerCounterpartyName, viewerDirection, perspective, salesAmount, returnAmount, salesOrderCount,
                statusText, false);
    }

    public ContractRespDTO(String id, String contractNo, String companyId, String counterpartyCompanyId,
                           String counterpartyName, String direction, String name, String templateName,
                           BigDecimal amount, String startDate, String endDate, String terms, String status,
                           Integer versionNo, String initiatedBy, String approvedBy, String approvedAt, String createdAt,
                           String supplierCompanyName, String buyerCompanyName, String viewerCompanyId,
                           String viewerCounterpartyCompanyId, String viewerCounterpartyName, String viewerDirection,
                           String perspective, BigDecimal salesAmount, BigDecimal returnAmount, int salesOrderCount) {
        this(id, contractNo, companyId, counterpartyCompanyId, counterpartyName, direction, name, templateName,
                amount, startDate, endDate, terms, status, versionNo, initiatedBy, approvedBy, approvedAt, createdAt,
                supplierCompanyName, buyerCompanyName, viewerCompanyId, viewerCounterpartyCompanyId,
                viewerCounterpartyName, viewerDirection, perspective, salesAmount, returnAmount, salesOrderCount, null);
    }

    public ContractRespDTO withStatusText(String value) {
        return new ContractRespDTO(id, contractNo, companyId, counterpartyCompanyId, counterpartyName, direction,
                name, templateName, amount, startDate, endDate, terms, status, versionNo, initiatedBy, approvedBy,
                approvedAt, createdAt, supplierCompanyName, buyerCompanyName, viewerCompanyId,
                viewerCounterpartyCompanyId, viewerCounterpartyName, viewerDirection, perspective,
                salesAmount, returnAmount, salesOrderCount, value, abolishPending, signingStatus);
    }

    public ContractRespDTO withAbolishProgress(String value) {
        return new ContractRespDTO(id, contractNo, companyId, counterpartyCompanyId, counterpartyName, direction,
                name, templateName, amount, startDate, endDate, terms, status, versionNo, initiatedBy, approvedBy,
                approvedAt, createdAt, supplierCompanyName, buyerCompanyName, viewerCompanyId,
                viewerCounterpartyCompanyId, viewerCounterpartyName, viewerDirection, perspective,
                salesAmount, returnAmount, salesOrderCount, value, value != null && !value.isBlank(), signingStatus);
    }

    public ContractRespDTO withSigningProgress(String code, String text) {
        return new ContractRespDTO(id, contractNo, companyId, counterpartyCompanyId, counterpartyName, direction,
                name, templateName, amount, startDate, endDate, terms, status, versionNo, initiatedBy, approvedBy,
                approvedAt, createdAt, supplierCompanyName, buyerCompanyName, viewerCompanyId,
                viewerCounterpartyCompanyId, viewerCounterpartyName, viewerDirection, perspective,
                salesAmount, returnAmount, salesOrderCount, text == null ? statusText : text, abolishPending, code);
    }

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
