package com.tradepass.module.trade.api.order.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TradeOrderRespDTO(
        String id,
        String direction,
        String counterpartyName,
        String orderNo,
        BigDecimal amount,
        LocalDate orderDate,
        String status
) {
    public TradeOrderRespDTO(String id, String direction, String counterpartyName, BigDecimal amount,
                             LocalDate orderDate, String status) {
        this(id, direction, counterpartyName, null, amount, orderDate, status);
    }
}
