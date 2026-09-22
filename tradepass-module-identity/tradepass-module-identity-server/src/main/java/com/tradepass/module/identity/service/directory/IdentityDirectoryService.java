package com.tradepass.module.identity.service.directory;

import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public interface IdentityDirectoryService {
    Map<Long, String> companyNames(List<Long> ids);
    List<Long> companyIdsNamed(String name);
    String userDisplayName(long userId);
    Map<Long, String> userNicknames(List<Long> ids);
    List<Counterparty> counterparties(long companyId);
    List<String> orderedCompanyNames(long companyAId, long companyBId);
}
