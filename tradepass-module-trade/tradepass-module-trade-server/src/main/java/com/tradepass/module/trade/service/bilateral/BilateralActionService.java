package com.tradepass.module.trade.service.bilateral;

import com.tradepass.module.contract.api.state.ContractStateOperations;
import com.tradepass.module.contract.api.state.ContractStateOperations.*;
import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations;
import com.tradepass.module.settlement.api.attachment.AttachmentStateOperations.*;
import com.tradepass.module.trade.api.bilateral.BilateralActionOperations;
import com.tradepass.module.trade.api.bilateral.BilateralActionOperations.*;
import com.tradepass.module.trade.service.approval.ApprovalService;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryService;
import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperations;
import com.tradepass.module.contract.api.abolish.ContractAbolishRecoveryOperations.*;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations.*;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations.*;
import com.tradepass.framework.mybatis.core.ApplicationIds;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public interface BilateralActionService {
    public static final String CONTRACT = "CONTRACT";
    public static final String ATTACHMENT = "ATTACHMENT";
    public static final String BUSINESS_DOCUMENT = "BUSINESS_DOCUMENT";
    public static final String END = "END";
    public static final String VOID = "VOID";
    public static final String RESUME = "RESUME";

    void setAbolishRecoveryService(ContractAbolishRecoveryOperations service);
    Map<String, Object> request(String bizType, Long bizId, String actionType, String reason, boolean riskConfirmed);
    Map<String, Object> decide(Long id, String decision, String reason);
    String cancel(Long id);
    Map<String, Object> active(String bizType, Long bizId);
    ActionState state(long companyId, String bizType, Long bizId);
    void requireContractMutable(TradeContractRespDTO contract);
    boolean isContractReadOnly(TradeContractRespDTO contract);
}
