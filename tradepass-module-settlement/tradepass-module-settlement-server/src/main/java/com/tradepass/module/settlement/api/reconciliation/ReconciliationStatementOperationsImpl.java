package com.tradepass.module.settlement.api.reconciliation;

import com.tradepass.framework.common.util.FileTypeInspector;
import java.util.List;
import java.util.Map;
import com.tradepass.module.settlement.service.reconciliation.ReconciliationStatementService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationStatementOperationsImpl implements ReconciliationStatementOperations {
    private final ReconciliationStatementService delegate;
    public ReconciliationStatementOperationsImpl(@Lazy ReconciliationStatementService delegate) { this.delegate = delegate; }
    @Override public List<Map<String, Object>> list(Long counterpartyCompanyId) { return delegate.list(counterpartyCompanyId); }
    @Override public Map<String, Object> upload(Long counterpartyCompanyId, String period, String remark,
                                      String originalName, byte[] data) { return delegate.upload(counterpartyCompanyId, period, remark, originalName, data); }
    @Override public FileRespDTO getFile(Long id) { return delegate.getFile(id); }
}
