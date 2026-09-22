package com.tradepass.module.contract.api.tenant;

import com.tradepass.module.contract.service.tenant.ContractTenantService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ContractTenantOperationsImpl implements ContractTenantOperations {
    private final ContractTenantService delegate;
    public ContractTenantOperationsImpl(@Lazy ContractTenantService delegate) { this.delegate = delegate; }
    @Override public void initialize(long companyId, long operatorUserId) { delegate.initialize(companyId, operatorUserId); }
}
