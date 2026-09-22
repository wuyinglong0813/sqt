package com.tradepass.module.identity.api.directory;

import java.util.List;
import java.util.Map;

/** Live join data. Names are not cached or copied into another service's database. */
public interface IdentityDirectoryOperations {
    public Map<Long, String> companyNames(List<Long> companyIds);
    public List<Long> companyIdsNamed(String name);
    public String userDisplayName(long userId);
    public Map<Long, String> userNicknames(List<Long> userIds);
    public List<Counterparty> counterparties(long companyId);
    public List<String> orderedCompanyNames(long companyAId, long companyBId);
    public record Counterparty(long companyId, String name) { }
}
