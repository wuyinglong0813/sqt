package com.tradepass.module.contract.service.abolish;

import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperations;
import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperations.*;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations;
import com.tradepass.module.trade.api.bilateral.BilateralStateOperations.*;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface ContractAbolishRecoveryService {
    public static boolean stopped(String status) {
            return "task_terminated".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status)
                    || "task_expired".equalsIgnoreCase(status);
        }

    void resumeAfterBilateralApproval(Long contractId);
}
