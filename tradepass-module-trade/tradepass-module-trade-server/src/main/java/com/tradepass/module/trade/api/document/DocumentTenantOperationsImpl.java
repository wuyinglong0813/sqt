package com.tradepass.module.trade.api.document;

import com.tradepass.module.trade.service.document.DocumentTenantService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class DocumentTenantOperationsImpl implements DocumentTenantOperations {
    private final DocumentTenantService delegate;
    public DocumentTenantOperationsImpl(@Lazy DocumentTenantService delegate) { this.delegate = delegate; }
    @Override public void initialize(long companyId, long operatorUserId) { delegate.initialize(companyId, operatorUserId); }
}
