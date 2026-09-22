package com.tradepass.module.trade.controller.app.order.vo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateOrderReqVO(
        @NotBlank @Pattern(regexp = "SALE|PURCHASE", message = "交易方向只能是 SALE 或 PURCHASE") String direction,
        @NotBlank String counterpartyName,
        @NotNull @DecimalMin(value = "0.00", message = "订单金额不能为负数") BigDecimal amount,
        @NotNull LocalDate orderDate,
        Long counterpartyCompanyId,
        @Size(max = 64) String orderNo,
        @Size(max = 64) String clientRequestId
) {
    public CreateOrderReqVO(String direction, String counterpartyName, BigDecimal amount, LocalDate orderDate) {
        this(direction, counterpartyName, amount, orderDate, null, null, null);
    }
}
