package com.tradepass.module.settlement.api.reconciliation;

import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.tradepass.module.settlement.service.reconciliation.ReconciliationAccountService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationAccountOperationsImpl implements ReconciliationAccountOperations {
    private final ReconciliationAccountService delegate;
    public ReconciliationAccountOperationsImpl(@Lazy ReconciliationAccountService delegate) { this.delegate = delegate; }
    @Override public List<Map<String, Object>> listAccounts() { return delegate.listAccounts(); }
    @Override public List<Map<String, Object>> listAccounts(String role) { return delegate.listAccounts(role); }
    @Override public Map<String, Object> account(Long counterpartyCompanyId) { return delegate.account(counterpartyCompanyId); }
    @Override public Map<String, Object> account(Long counterpartyCompanyId, String role) { return delegate.account(counterpartyCompanyId, role); }
    @Override public WorkbookRespDTO workbook(Long counterpartyCompanyId) { return delegate.workbook(counterpartyCompanyId); }
    @Override public void recordSalesOrder(BusinessDocumentRespDTO document, BigDecimal amount,
                                 LocalDate businessDate, long approvedBy,
                                 LocalDateTime approvedAt) { delegate.recordSalesOrder(document, amount, businessDate, approvedBy, approvedAt); }
    @Override public void recordReturnOrder(BusinessDocumentRespDTO document, BigDecimal amount,
                                  LocalDate businessDate, long approvedBy,
                                  LocalDateTime approvedAt) { delegate.recordReturnOrder(document, amount, businessDate, approvedBy, approvedAt); }
    @Override public void recordAttachment(TradeContractRespDTO contract, String sourceType, long sourceId,
                                 LocalDate businessDate, String documentNo, BigDecimal amount,
                                 long issuerCompanyId, long approvedBy, LocalDateTime approvedAt) { delegate.recordAttachment(contract, sourceType, sourceId, businessDate, documentNo, amount, issuerCompanyId, approvedBy, approvedAt); }
    @Override public void reverseSource(String sourceType, long sourceId, long actionRequestId,
                              long approvedBy, LocalDateTime approvedAt) { delegate.reverseSource(sourceType, sourceId, actionRequestId, approvedBy, approvedAt); }
}
