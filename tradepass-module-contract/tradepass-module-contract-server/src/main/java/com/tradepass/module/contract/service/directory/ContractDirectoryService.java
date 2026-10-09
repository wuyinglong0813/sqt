package com.tradepass.module.contract.service.directory;

import static com.tradepass.module.contract.api.directory.ContractDirectoryOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations;
import com.tradepass.module.contract.api.directory.ContractDirectoryOperations.*;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.framework.common.pojo.TradePassDtos.RankingItem;
import com.tradepass.framework.common.pojo.TradePassDtos.CounterpartyContractCount;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

public interface ContractDirectoryService {
    java.math.BigDecimal partySalesAmount(long companyId);
    List<TradeContractDO> contractsByIds(List<Long> ids);
    List<TradeContractDO> activePartyContracts(long companyId);
    Long activePartyContractId(long companyId, Long contractId);
    List<TradeContractDO> partyContracts(long companyId, String name, String status, int limit, long offset);
    List<TradeContractDO> partyContracts(long companyId, String name, String status, String viewerDirection, int limit, long offset);
    long partyContractCount(long companyId, String name, String status);
    long partyContractCount(long companyId, String name, String status, String viewerDirection);
    List<RankingItem> signedTradeRanking(long companyId, String viewerDirection, String period);
    List<CounterpartyContractCount> signedTradeContractCounts(long companyId, String viewerDirection);
}
