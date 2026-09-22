package com.tradepass.module.trade.service.inventory;

import com.tradepass.module.trade.convert.document.BusinessDocumentConvert;
import com.tradepass.module.trade.service.approval.ApprovalService;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations.*;
import com.tradepass.framework.mybatis.core.ApplicationIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public interface SalesOrderInventoryService {
    public static final String RECEIVE_ONLY = "RECEIVE_ONLY";
    public static final String INBOUND = "INBOUND";
    public static final String REJECT = "REJECT";

    void setApprovalService(ApprovalService approvalService);
    void saveDocumentItems(BusinessDocumentDO document);
    void selectReturnWarehouse(BusinessDocumentDO document, long companyId, Long warehouseId);
    Map<String, Object> documentDetail(Long documentId);
    List<Map<String, Object>> listWarehouses();
    Map<String, Object> createWarehouse(String name, String address);
    Map<String, Object> inventoryOverview();
    Map<String, Object> manualInbound(Long warehouseId, String requestId, String name, String specification, String unit, BigDecimal quantity, BigDecimal price, String remark);
    List<Map<String, Object>> searchProducts(String keyword, Integer limit);
    Map<String, Object> receive(Long documentId, String decision, Long warehouseId);
    Map<String, Object> receive(Long documentId, String decision, Long warehouseId, String rejectedReason);
    Map<String, Object> receive(Long documentId, String decision, Long warehouseId, String rejectedReason, String signatureName, byte[] signatureData);
    void reverseDocumentInventory(Long documentId, long actionRequestId, long userId);
}
