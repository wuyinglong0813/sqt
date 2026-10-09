package com.tradepass.module.trade.api.ranking;

import java.math.BigDecimal;
import java.util.Map;

public interface SalesPerformanceOperations {
    public record ContractSales(BigDecimal salesAmount, BigDecimal returnAmount, int salesOrderCount) {}
    public Map<String, ContractSales> contractSales(long companyId);
}
