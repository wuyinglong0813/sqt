package com.tradepass.module.trade.api.ranking;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.trade.dal.mysql.order.TradeOrderMapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SalesPerformanceOperationsImpl implements SalesPerformanceOperations {
    private final TradeOrderMapper orders;
    private final AccessControlOperations access;
    public SalesPerformanceOperationsImpl(TradeOrderMapper orders, AccessControlOperations access) {
        this.orders = orders; this.access = access;
    }
    public Map<String, ContractSales> contractSales(long companyId) {
        access.resolveCompanyId(String.valueOf(companyId));
        Map<String, ContractSales> result = new LinkedHashMap<>();
        for (var row : orders.selectContractSales(companyId)) {
            result.put(String.valueOf(row.get("contractId")), new ContractSales(
                    (BigDecimal) row.get("salesAmount"), (BigDecimal) row.get("returnAmount"),
                    ((Number) row.get("salesOrderCount")).intValue()));
        }
        return result;
    }
}
