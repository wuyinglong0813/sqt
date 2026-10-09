package com.tradepass.module.trade.service.retail;

import java.math.BigDecimal;
import java.util.List;

public final class RetailDtos {
    private RetailDtos() {}
    public record CustomerRequest(String customerType, String name, String contact, String phone,
                                  String address, String invoiceTitle, String taxNo, String remark) {}
    public record ItemRequest(Long originalItemId, String lineType, String productName, String specification,
                              String baseUnit, BigDecimal quantity, BigDecimal unitPrice, String remark) {}
    public record DocumentRequest(String requestId, String documentType, Long originalDocumentId,
                                  String orderDate, String remark, List<ItemRequest> items) {}
    public record ConfirmRequest(Long warehouseId) {}
}
