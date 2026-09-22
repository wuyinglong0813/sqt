package com.tradepass.module.settlement.service.attachment;

import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations.*;
import com.tradepass.module.settlement.service.reconciliation.ReconciliationAccountService;
import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.module.trade.api.approval.ApprovalOperations;
import com.tradepass.module.trade.api.approval.ApprovalOperations.*;
import com.tradepass.module.trade.api.bilateral.BilateralActionOperations;
import com.tradepass.module.trade.api.bilateral.BilateralActionOperations.*;
import com.tradepass.framework.mybatis.core.ApplicationIds;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.module.settlement.dal.dataobject.attachment.ContractAttachmentDO;
import com.tradepass.module.settlement.dal.mysql.attachment.ContractAttachmentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.tradepass.framework.storage.config.StorageProperties;

public interface ContractAttachmentService {
    public static final String PAYMENT_VOUCHER = "PAYMENT_VOUCHER";
    public static final String INVOICE = "INVOICE";
    public static final String OTHER = "OTHER";

    void setApprovalService(ApprovalOperations approvalService);
    void setBilateralActionService(BilateralActionOperations bilateralActionService);
    List<Map<String, Object>> list(Long contractId, String category);
    Map<String, Object> upload(Long contractId, String category, String originalName, byte[] data, String voucherDate, String voucherAmount);
    Map<String, Object> upload(Long contractId, String category, String originalName, byte[] data, String voucherDate, String voucherAmount, String invoiceNo, String invoiceDate, String invoiceAmount);
    Map<String, Object> decide(Long id, String decision, String reason);
    Map<String, Object> decide(Long id, String decision, String reason, String signatureName, byte[] signatureData);
    String withdraw(Long id);
    String delete(Long id);
    FileRespDTO getFile(Long id);
}
