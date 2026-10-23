package com.tradepass.module.contract.service.abolish;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Compatibility entry point: approved local voids cannot be reopened. */
@Service
public class ContractAbolishRecoveryServiceImpl implements ContractAbolishRecoveryService {
    private final TradeContractMapper contracts;
    private final AccessControlOperations access;

    public ContractAbolishRecoveryServiceImpl(TradeContractMapper contracts,
            FadadaContractSignTaskMapper tasks, FadadaSigningGateway gateway,
            AccessControlOperations access, JdbcTemplate jdbc) {
        this.contracts = contracts;
        this.access = access;
    }

    public void resumeAfterBilateralApproval(Long contractId) {
        long companyId = AuthContext.requireCompanyId();
        access.requirePermission(companyId, "contract_sign");
        var contract = contracts.selectById(contractId);
        if (contract == null || (!Long.valueOf(companyId).equals(contract.getCompanyId())
                && !Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId()))) {
            throw new BusinessException("合同不存在");
        }
        throw new BusinessException("合同作废已改为商签通内双方确认，已作废合同不能恢复履约");
    }
}
