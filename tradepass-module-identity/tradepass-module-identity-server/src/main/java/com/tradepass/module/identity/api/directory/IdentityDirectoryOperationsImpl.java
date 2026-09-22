package com.tradepass.module.identity.api.directory;

import java.util.List;
import java.util.Map;
import com.tradepass.module.identity.service.directory.IdentityDirectoryService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class IdentityDirectoryOperationsImpl implements IdentityDirectoryOperations {
    private final IdentityDirectoryService delegate;
    public IdentityDirectoryOperationsImpl(@Lazy IdentityDirectoryService delegate) { this.delegate = delegate; }
    @Override public Map<Long, String> companyNames(List<Long> companyIds) { return delegate.companyNames(companyIds); }
    @Override public List<Long> companyIdsNamed(String name) { return delegate.companyIdsNamed(name); }
    @Override public String userDisplayName(long userId) { return delegate.userDisplayName(userId); }
    @Override public Map<Long, String> userNicknames(List<Long> userIds) { return delegate.userNicknames(userIds); }
    @Override public List<Counterparty> counterparties(long companyId) { return delegate.counterparties(companyId); }
    @Override public List<String> orderedCompanyNames(long companyAId, long companyBId) { return delegate.orderedCompanyNames(companyAId, companyBId); }
}
