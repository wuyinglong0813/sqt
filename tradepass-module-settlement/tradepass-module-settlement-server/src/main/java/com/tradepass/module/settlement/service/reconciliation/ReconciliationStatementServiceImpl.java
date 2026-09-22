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

@Service
public class ReconciliationStatementServiceImpl implements ReconciliationStatementService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tradepass.module.identity.api.directory.IdentityDirectoryOperations identityDirectory;

    private final ReconciliationStatementMapper statementMapper;
    private final CounterpartyReader relationMapper;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;
    private final ObjectStorageService objectStorageService;
    private final StorageProperties storageProperties;

    @Autowired
    public ReconciliationStatementServiceImpl(ReconciliationStatementMapper statementMapper,
                                          CounterpartyReader relationMapper,
                                          AccessControlOperations accessControlService,
                                          AuditLogService auditLogService,
                                          ObjectStorageService objectStorageService,
                                          StorageProperties storageProperties) {
        this.statementMapper = statementMapper;
        this.relationMapper = relationMapper;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.objectStorageService = objectStorageService;
        this.storageProperties = storageProperties;
    }

    ReconciliationStatementServiceImpl(ReconciliationStatementMapper statementMapper,
                                   CounterpartyReader relationMapper,
                                   AccessControlOperations accessControlService,
                                   AuditLogService auditLogService) {
        this(statementMapper, relationMapper, accessControlService, auditLogService, null, null);
    }

    public List<Map<String, Object>> list(Long counterpartyCompanyId) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "reconciliation");
        if (counterpartyCompanyId == null) {
            return queryStatements(companyId, null);
        }
        requireRelation(companyId, counterpartyCompanyId);
        return queryStatements(companyId, counterpartyCompanyId);
    }

    @Transactional
    public Map<String, Object> upload(Long counterpartyCompanyId, String period, String remark,
                                      String originalName, byte[] data) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "reconciliation");
        requireRelation(companyId, counterpartyCompanyId);
        String normalizedPeriod = normalizePeriod(period);
        String contentType = FileTypeInspector.inspect(data);
        if (!FileTypeInspector.isXlsx(contentType)) {
            throw new BusinessException("客户对账单仅支持 XLSX 文件");
        }
        String safeName = FileTypeInspector.sanitizeFileName(originalName, contentType);
        String safeRemark = remark == null ? "" : remark.trim();
        if (safeRemark.length() > 500) throw new BusinessException("备注不能超过 500 字");
        String sha256 = FileTypeInspector.sha256(data);
        ObjectStorageService.StoredObject stored = store(companyId, counterpartyCompanyId,
                normalizedPeriod, contentType, data, sha256);
        Long id = ApplicationIds.next();
        ReconciliationStatementDO row = new ReconciliationStatementDO();
        row.setId(id);
        row.setIssuerCompanyId(companyId);
        row.setCounterpartyCompanyId(counterpartyCompanyId);
        row.setStatementPeriod(normalizedPeriod);
        row.setOriginalName(safeName);
        row.setContentType(contentType);
        row.setFileSize((long) data.length);
        row.setSha256(sha256);
        row.setRemark(safeRemark);
        row.setCreatedBy(AuthContext.userId());
        if (stored == null) {
            row.setFileData(data);
        } else {
            row.setStorageProvider(stored.provider());
            row.setStorageBucket(stored.bucket());
            row.setObjectKey(stored.objectKey());
            row.setObjectVersionId(stored.versionId());
            row.setEtag(stored.etag());
            row.setEncryptionAlgorithm(stored.encryptionAlgorithm());
        }
        statementMapper.insert(row);
        auditLogService.log(companyId, "RECONCILIATION_STATEMENT", id,
                "UPLOAD", "上传 " + normalizedPeriod + " 客户对账单 " + safeName);
        return queryStatements(companyId, counterpartyCompanyId).stream()
                .filter(item -> id != null && id.equals(item.get("id")))
                .findFirst().orElseThrow(() -> new BusinessException("对账单保存失败"));
    }

    public FileRespDTO getFile(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requirePermission(companyId, "reconciliation");
        ReconciliationStatementDO storedFile = statementMapper.selectFile(id);
        if (storedFile == null) throw new BusinessException("对账单不存在");
        FileRespDTO file = new FileRespDTO(
                storedFile.getId(), storedFile.getIssuerCompanyId(), storedFile.getCounterpartyCompanyId(),
                storedFile.getOriginalName(), storedFile.getContentType(), storedFile.getFileData(),
                storedFile.getStorageBucket(), storedFile.getObjectKey(), storedFile.getObjectVersionId(),
                storedFile.getFileSize(), storedFile.getSha256());
        if (!Long.valueOf(companyId).equals(file.issuerCompanyId())
                && !Long.valueOf(companyId).equals(file.counterpartyCompanyId())) {
            throw new BusinessException("对账单不存在");
        }
        if (file.data() != null) return file;
        if (objectStorageService == null || !objectStorageService.isEnabled()
                || file.objectKey() == null) {
            throw new BusinessException("对账单内容暂不可用，请联系管理员");
        }
        byte[] data = objectStorageService.get(new ObjectStorageService.ObjectReference(
                file.storageBucket(), file.objectKey(), file.objectVersionId(),
                file.fileSize(), file.sha256()));
        return file.withData(data);
    }

    private ObjectStorageService.StoredObject store(long companyId, Long counterpartyCompanyId,
                                                     String period, String contentType,
                                                     byte[] data, String sha256) {
        if (objectStorageService == null || !objectStorageService.isEnabled()) return null;
        String key = keyPrefix() + "/file/" + companyId + "/reconciliation/"
                + counterpartyCompanyId + "/" + period + "/" + UUID.randomUUID() + "-"
                + sha256 + "." + FileTypeInspector.extension(contentType);
        return objectStorageService.putImmutable(key, data, contentType, sha256);
    }

    private String keyPrefix() {
        String value = storageProperties == null ? "tradepass" : storageProperties.getKeyPrefix();
        value = value == null ? "" : value.trim().replaceAll("^/+|/+$", "");
        return value.isBlank() ? "tradepass" : value;
    }

    private List<Map<String, Object>> queryStatements(long companyId, Long counterpartyCompanyId) {
        List<ReconciliationStatementDO> rows;
        if (identityDirectory == null) {
            rows = counterpartyCompanyId == null
                    ? statementMapper.selectByCompany(companyId)
                    : statementMapper.selectByCompanyPair(companyId, counterpartyCompanyId);
        } else {
            rows = counterpartyCompanyId == null
                    ? statementMapper.selectByCompanyWithoutJoin(companyId)
                    : statementMapper.selectByCompanyPairWithoutJoin(companyId, counterpartyCompanyId);
        }
        return rows.stream().map(statement -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", statement.getId());
            view.put("issuerCompanyId", statement.getIssuerCompanyId());
            view.put("counterpartyCompanyId", statement.getCounterpartyCompanyId());
            if (identityDirectory == null) {
                view.put("issuerCompanyName", statement.getIssuerCompanyName());
                view.put("counterpartyName", statement.getCounterpartyName());
            } else {
                long issuer = statement.getIssuerCompanyId(), counterparty = statement.getCounterpartyCompanyId();
                var names = identityDirectory.companyNames(List.of(issuer, counterparty));
                if (!names.containsKey(issuer) || !names.containsKey(counterparty)) return null;
                view.put("issuerCompanyName", names.get(issuer));
                view.put("counterpartyName", names.get(issuer == companyId ? counterparty : issuer));
            }
            view.put("statementPeriod", statement.getStatementPeriod());
            view.put("originalName", statement.getOriginalName());
            view.put("contentType", statement.getContentType());
            view.put("fileSize", statement.getFileSize());
            view.put("remark", statement.getRemark());
            view.put("createdAt", statement.getCreatedAt());
            return view;
        }).filter(java.util.Objects::nonNull).toList();
    }

    private void requireRelation(long companyId, Long counterpartyCompanyId) {
        if (counterpartyCompanyId == null || companyId == counterpartyCompanyId) {
            throw new BusinessException("请选择合作企业");
        }
        long count = relationMapper.countActiveBetween(companyId, counterpartyCompanyId);
        if (count == 0) throw new BusinessException("合作企业关系不存在");
    }

    private String normalizePeriod(String period) {
        String value = period == null ? "" : period.trim();
        if (!value.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new BusinessException("对账期间格式应为 YYYY-MM");
        }
        return value;
    }

    
}
