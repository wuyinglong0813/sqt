package com.tradepass.module.contract.service.state;

import com.tradepass.module.contract.api.state.ContractStateOperations;
import com.tradepass.module.contract.api.state.ContractStateOperations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

public interface ContractStateService {
    long electronicTaskCount(Long contractId);
    int changeActiveStatus(Long contractId, String nextStatus);
}
