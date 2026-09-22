package com.tradepass.module.settlement.service.reconciliation;

import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations;
import com.tradepass.module.identity.api.directory.IdentityDirectoryOperations.*;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationStatementOperations;
import com.tradepass.module.settlement.api.reconciliation.ReconciliationStatementOperations.*;
import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.mybatis.core.ApplicationIds;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.api.counterparty.CounterpartyReader;
import com.tradepass.module.identity.api.counterparty.CounterpartyReader.*;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationStatementDO;
import com.tradepass.module.settlement.dal.mysql.reconciliation.ReconciliationStatementMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.tradepass.framework.storage.config.StorageProperties;

public interface ReconciliationStatementService {
    List<Map<String, Object>> list(Long counterpartyCompanyId);
    Map<String, Object> upload(Long counterpartyCompanyId, String period, String remark, String originalName, byte[] data);
    FileRespDTO getFile(Long id);
}
