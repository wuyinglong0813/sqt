package com.tradepass.module.settlement.api.reconciliation;

import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface ReconciliationAccountOperations {
    public static final String SALES_ORDER = "SALES_ORDER";

    public static final String RETURN_ORDER = "RETURN_ORDER";

    public static final String PAYMENT_VOUCHER = "PAYMENT_VOUCHER";

    public static final String INVOICE = "INVOICE";

    public List<Map<String, Object>> listAccounts();

    public List<Map<String, Object>> listAccounts(String role);

    public Map<String, Object> account(Long counterpartyCompanyId);

    public Map<String, Object> account(Long counterpartyCompanyId, String role);

    public WorkbookRespDTO workbook(Long counterpartyCompanyId);

    public void recordSalesOrder(BusinessDocumentRespDTO document, BigDecimal amount,
                                 LocalDate businessDate, long approvedBy,
                                 LocalDateTime approvedAt);

    public void recordReturnOrder(BusinessDocumentRespDTO document, BigDecimal amount,
                                  LocalDate businessDate, long approvedBy,
                                  LocalDateTime approvedAt);

    public void recordAttachment(TradeContractRespDTO contract, String sourceType, long sourceId,
                                 LocalDate businessDate, String documentNo, BigDecimal amount,
                                 long issuerCompanyId, long approvedBy, LocalDateTime approvedAt);

    public void reverseSource(String sourceType, long sourceId, long actionRequestId,
                              long approvedBy, LocalDateTime approvedAt);

    public record WorkbookRespDTO(String originalName, String contentType, byte[] data) {
    }
}
