package com.tradepass.module.trade.service.logistics;

import com.tradepass.module.trade.service.bilateral.BilateralActionService;
import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.dal.dataobject.logistics.LogisticsDocumentDO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.trade.dal.mysql.logistics.LogisticsDocumentMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.framework.storage.config.StorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface LogisticsDocumentService {
    public static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;

    void setBilateralActionService(BilateralActionService bilateralActionService);
    List<Map<String, Object>> listDocuments(Long contractId);
    Map<String, Object> upload(Long contractId, String originalName, byte[] imageData);
    LogisticsDocumentDO getImage(Long id);
    String delete(Long id);
}
