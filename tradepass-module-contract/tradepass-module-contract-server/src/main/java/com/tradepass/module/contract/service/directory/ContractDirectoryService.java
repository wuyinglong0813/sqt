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

public interface ContractDirectoryService {
    List<TradeContractDO> contractsByIds(List<Long> ids);
    List<TradeContractDO> activePartyContracts(long companyId);
    Long activePartyContractId(long companyId, Long contractId);
    List<TradeContractDO> partyContracts(long companyId, String name, String status, int limit, long offset);
    long partyContractCount(long companyId, String name, String status);
}
