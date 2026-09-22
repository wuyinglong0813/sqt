package com.tradepass.module.contract.service.contract;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.convert.contract.TradeContractConvert;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.Serializable;

public interface ContractReadService {
    TradeContractRespDTO selectById(Serializable id);
    TradeContractRespDTO selectByIdForUpdate(Long id);
    long countContractsAwaitingSignature(long companyId);
}
