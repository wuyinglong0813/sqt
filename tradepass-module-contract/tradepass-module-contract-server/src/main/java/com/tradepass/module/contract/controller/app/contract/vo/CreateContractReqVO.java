package com.tradepass.module.contract.controller.app.contract.vo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateContractReqVO(
        @NotBlank String counterpartyName,
        @NotBlank String name,
        String templateName,
        @NotNull @DecimalMin(value = "0.00", message = "合同金额不能为负数") BigDecimal amount,
        String startDate,
        String endDate,
        String terms,
        @NotNull Long counterpartyCompanyId,
        @NotBlank @Pattern(regexp = "SALE|PURCHASE", message = "交易方向只能是 SALE 或 PURCHASE") String direction,
        @Size(max = 64) String contractNo,
        @NotBlank @Size(max = 64) String clientRequestId
) {
}
