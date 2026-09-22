package com.tradepass.module.contract.service.directory;

import static com.tradepass.module.contract.api.directory.ContractDirectoryOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations.*;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ContractDirectoryServiceImpl implements ContractDirectoryService {
    private final TradeContractMapper contracts;
    private final IdentityDirectoryOperations identity;
    private final JdbcTemplate jdbc;
    public ContractDirectoryServiceImpl(TradeContractMapper contracts, IdentityDirectoryOperations identity, JdbcTemplate jdbc) {
        this.contracts = contracts; this.identity = identity; this.jdbc = jdbc;
    }
    public List<TradeContractDO> contractsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return contracts.selectList(new LambdaQueryWrapper<TradeContractDO>().in(TradeContractDO::getId, ids));
    }
    public List<TradeContractDO> activePartyContracts(long companyId) {
        var candidates = contracts.selectList(new LambdaQueryWrapper<TradeContractDO>()
                .eq(TradeContractDO::getStatus, "ACTIVE")
                .and(q -> q.eq(TradeContractDO::getCompanyId, companyId).or().eq(TradeContractDO::getCounterpartyCompanyId, companyId))
                .last("ORDER BY COALESCE(approved_at, created_at) DESC, id DESC"));
        var names = identity.companyNames(candidates.stream().map(TradeContractDO::getCompanyId).distinct().toList());
        return candidates.stream().filter(c -> names.containsKey(c.getCompanyId())).toList();
    }
    public Long activePartyContractId(long companyId, Long contractId) {
        TradeContractDO value = contracts.selectById(contractId);
        return value != null && "ACTIVE".equals(value.getStatus())
                && (Long.valueOf(companyId).equals(value.getCompanyId()) || Long.valueOf(companyId).equals(value.getCounterpartyCompanyId())) ? contractId : null;
    }
    public List<TradeContractDO> partyContracts(long companyId, String name, String status, int limit, long offset) {
        if (limit < 1 || limit > 1000 || offset < 0) throw new IllegalArgumentException("Invalid contract page");
        return contracts.selectList(partyQuery(companyId, name, status)
                .orderByDesc(TradeContractDO::getCreatedAt).orderByDesc(TradeContractDO::getId)
                .last("LIMIT " + limit + " OFFSET " + offset));
    }
    public long partyContractCount(long companyId, String name, String status) {
        return contracts.selectCount(partyQuery(companyId, name, status));
    }
    private LambdaQueryWrapper<TradeContractDO> partyQuery(long companyId, String name, String status) {
        // Preserve the original INNER JOIN, including removal of rows with missing initiators.
        var initiators = jdbc.queryForList("SELECT DISTINCT company_id FROM trade_contract WHERE company_id = ? OR counterparty_company_id = ?", Long.class, companyId, companyId);
        var existingIds = identity.companyNames(initiators).keySet();
        var query = new LambdaQueryWrapper<TradeContractDO>();
        query.in(TradeContractDO::getCompanyId, existingIds.isEmpty() ? List.of(-1L) : existingIds);
        query.and(q -> q.and(a -> a.eq(TradeContractDO::getCompanyId, companyId)
                        .and(h -> h.eq(TradeContractDO::getInitiatorHidden, false).or().isNull(TradeContractDO::getInitiatorHidden)))
                .or(a -> a.eq(TradeContractDO::getCounterpartyCompanyId, companyId)
                        .notIn(TradeContractDO::getStatus, "REJECTED", "CANCELLED", "DELETED")));
        if (name != null && !name.isEmpty()) {
            var matchingIds = identity.companyIdsNamed(name);
            query.and(q -> q.and(a -> a.eq(TradeContractDO::getCompanyId, companyId).eq(TradeContractDO::getCounterpartyName, name))
                    .or(a -> a.eq(TradeContractDO::getCounterpartyCompanyId, companyId)
                            .in(TradeContractDO::getCompanyId, matchingIds.isEmpty() ? List.of(-1L) : matchingIds)));
        }
        if (status != null && !status.isEmpty()) query.eq(TradeContractDO::getStatus, status);
        return query;
    }
}
