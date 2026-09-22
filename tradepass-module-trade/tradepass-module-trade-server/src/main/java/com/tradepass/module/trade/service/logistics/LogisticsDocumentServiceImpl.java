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

@Service
public class LogisticsDocumentServiceImpl implements LogisticsDocumentService {
    public static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;

    private final LogisticsDocumentMapper documentMapper;
    private final ContractReader contractMapper;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;
    private final ObjectStorageService objectStorageService;
    private final StorageProperties storageProperties;
    private BilateralActionService bilateralActionService;

    @Autowired
    public LogisticsDocumentServiceImpl(LogisticsDocumentMapper documentMapper,
                                    ContractReader contractMapper,
                                    AccessControlOperations accessControlService,
                                    AuditLogService auditLogService,
                                    ObjectStorageService objectStorageService,
                                    StorageProperties storageProperties) {
        this.documentMapper = documentMapper;
        this.contractMapper = contractMapper;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.objectStorageService = objectStorageService;
        this.storageProperties = storageProperties;
    }

    LogisticsDocumentServiceImpl(LogisticsDocumentMapper documentMapper,
                             ContractReader contractMapper,
                             AccessControlOperations accessControlService,
                             AuditLogService auditLogService) {
        this(documentMapper, contractMapper, accessControlService, auditLogService, null, null);
    }

    @Autowired
    public void setBilateralActionService(BilateralActionService bilateralActionService) {
        this.bilateralActionService = bilateralActionService;
    }

    public List<Map<String, Object>> listDocuments(Long contractId) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_view", "contract_sign");
        TradeContractRespDTO contract = requireContractParty(contractId, companyId);
        boolean contractReadOnly = bilateralActionService != null
                ? bilateralActionService.isContractReadOnly(contract)
                : "COMPLETED".equals(contract.getStatus()) || "VOIDED".equals(contract.getStatus());
        return documentMapper.selectList(new LambdaQueryWrapper<LogisticsDocumentDO>()
                        .select(LogisticsDocumentDO::getId,
                                LogisticsDocumentDO::getCompanyId,
                                LogisticsDocumentDO::getContractId,
                                LogisticsDocumentDO::getOriginalName,
                                LogisticsDocumentDO::getContentType,
                                LogisticsDocumentDO::getFileSize,
                                LogisticsDocumentDO::getCreatedBy,
                                LogisticsDocumentDO::getCreatedAt)
                        .eq(LogisticsDocumentDO::getContractId, contractId)
                        .isNull(LogisticsDocumentDO::getDeletedAt)
                        .orderByDesc(LogisticsDocumentDO::getCreatedAt)
                        .orderByDesc(LogisticsDocumentDO::getId))
                .stream()
                .map(document -> documentView(document, contractReadOnly))
                .toList();
    }

    @Transactional
    public Map<String, Object> upload(Long contractId, String originalName, byte[] imageData) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_sign", "order_create");
        TradeContractRespDTO contract = requireContractParty(contractId, companyId);
        requireContractMutable(contract);
        if (imageData == null || imageData.length == 0) {
            throw new BusinessException("请选择物流单图片");
        }
        if (imageData.length > MAX_IMAGE_SIZE) {
            throw new BusinessException("物流单图片不能超过 10MB");
        }
        String contentType = detectImageContentType(imageData);
        if (contentType == null) {
            throw new BusinessException("仅支持 JPG、PNG、GIF 或 WebP 图片");
        }

        LogisticsDocumentDO document = new LogisticsDocumentDO();
        document.setCompanyId(companyId);
        document.setContractId(contractId);
        document.setOriginalName(normalizeFileName(originalName, contentType));
        document.setContentType(contentType);
        document.setFileSize((long) imageData.length);
        String sha256 = FileTypeInspector.sha256(imageData);
        document.setSha256(sha256);
        ObjectStorageService.StoredObject stored = store(companyId, contractId,
                contentType, imageData, sha256);
        if (stored == null) {
            document.setImageData(imageData);
        } else {
            document.setStorageProvider(stored.provider());
            document.setStorageBucket(stored.bucket());
            document.setObjectKey(stored.objectKey());
            document.setObjectVersionId(stored.versionId());
            document.setEtag(stored.etag());
            document.setEncryptionAlgorithm(stored.encryptionAlgorithm());
        }
        document.setCreatedBy(AuthContext.userId());
        documentMapper.insert(document);
        auditLogService.log(companyId, "LOGISTICS_DOCUMENT", document.getId(),
                "UPLOAD", "上传合同物流单图片 " + document.getOriginalName());

        LogisticsDocumentDO created = documentMapper.selectById(document.getId());
        return documentView(created == null ? document : created);
    }

    public LogisticsDocumentDO getImage(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_view", "contract_sign");
        LogisticsDocumentDO document = documentMapper.selectById(id);
        if (document == null || document.getDeletedAt() != null) {
            throw new BusinessException("物流单图片不存在");
        }
        requireContractParty(document.getContractId(), companyId);
        if (document.getImageData() == null) {
            if (objectStorageService == null || !objectStorageService.isEnabled()
                    || document.getObjectKey() == null) {
                throw new BusinessException("物流单图片暂不可用，请联系管理员");
            }
            document.setImageData(objectStorageService.get(new ObjectStorageService.ObjectReference(
                    document.getStorageBucket(), document.getObjectKey(), document.getObjectVersionId(),
                    document.getFileSize(), document.getSha256())));
        }
        return document;
    }

    @Transactional
    public String delete(Long id) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId, "contract_sign", "order_create");
        LogisticsDocumentDO document = documentMapper.selectById(id);
        if (document == null || document.getDeletedAt() != null
                || !Long.valueOf(companyId).equals(document.getCompanyId())
                || !Long.valueOf(AuthContext.userId()).equals(document.getCreatedBy())) {
            throw new BusinessException("仅上传人可以删除物流单");
        }
        TradeContractRespDTO contract = requireContractParty(document.getContractId(), companyId);
        requireContractMutable(contract);
        document.setDeletedBy(AuthContext.userId());
        document.setDeletedAt(java.time.LocalDateTime.now());
        documentMapper.updateById(document);
        auditLogService.log(companyId, "LOGISTICS_DOCUMENT", id, "DELETE",
                "删除合同物流单图片 " + document.getOriginalName());
        return "物流单已删除";
    }

    private ObjectStorageService.StoredObject store(long companyId, Long contractId, String contentType,
                                                     byte[] data, String sha256) {
        if (objectStorageService == null || !objectStorageService.isEnabled()) return null;
        java.time.LocalDate today = java.time.LocalDate.now();
        String key = keyPrefix() + "/file/" + companyId + "/" + contractId
                + "/logistics/" + today.getYear() + "/"
                + String.format("%02d", today.getMonthValue()) + "/"
                + UUID.randomUUID() + "-" + sha256 + "."
                + FileTypeInspector.extension(contentType);
        return objectStorageService.putImmutable(key, data, contentType, sha256);
    }

    private String keyPrefix() {
        String value = storageProperties == null ? "tradepass" : storageProperties.getKeyPrefix();
        value = value == null ? "" : value.trim().replaceAll("^/+|/+$", "");
        return value.isBlank() ? "tradepass" : value;
    }

    private TradeContractRespDTO requireContractParty(Long contractId, long companyId) {
        TradeContractRespDTO contract = contractMapper.selectById(contractId);
        if (contract == null
                || (!Long.valueOf(companyId).equals(contract.getCompanyId())
                && !Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId()))) {
            throw new BusinessException("合同不存在");
        }
        return contract;
    }

    private void requireContractMutable(TradeContractRespDTO contract) {
        if (bilateralActionService != null) {
            bilateralActionService.requireContractMutable(contract);
        } else if ("COMPLETED".equals(contract.getStatus()) || "VOIDED".equals(contract.getStatus())) {
            throw new BusinessException("合同已结束或作废，仅允许查看");
        }
    }

    private Map<String, Object> documentView(LogisticsDocumentDO document) {
        return documentView(document, false);
    }

    private Map<String, Object> documentView(LogisticsDocumentDO document, boolean contractReadOnly) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", document.getId());
        view.put("contractId", document.getContractId());
        view.put("uploaderCompanyId", document.getCompanyId());
        view.put("originalName", document.getOriginalName());
        view.put("contentType", document.getContentType());
        view.put("fileSize", document.getFileSize());
        view.put("contractReadOnly", contractReadOnly);
        view.put("canDelete", !contractReadOnly
                && Long.valueOf(AuthContext.requireCompanyId()).equals(document.getCompanyId())
                && Long.valueOf(AuthContext.userId()).equals(document.getCreatedBy()));
        view.put("createdAt", document.getCreatedAt());
        return view;
    }

    private String normalizeFileName(String originalName, String contentType) {
        String name = originalName == null ? "" : originalName
                .replace("\\", "_")
                .replace("/", "_")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        if (name.isBlank()) {
            name = "物流单." + extension(contentType);
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private String detectImageContentType(byte[] data) {
        if (data.length >= 3
                && unsigned(data[0]) == 0xff
                && unsigned(data[1]) == 0xd8
                && unsigned(data[2]) == 0xff) {
            return "image/jpeg";
        }
        if (data.length >= 8
                && unsigned(data[0]) == 0x89
                && data[1] == 'P'
                && data[2] == 'N'
                && data[3] == 'G'
                && unsigned(data[4]) == 0x0d
                && unsigned(data[5]) == 0x0a
                && unsigned(data[6]) == 0x1a
                && unsigned(data[7]) == 0x0a) {
            return "image/png";
        }
        if (data.length >= 6) {
            String signature = new String(data, 0, 6, StandardCharsets.US_ASCII);
            if ("GIF87a".equals(signature) || "GIF89a".equals(signature)) {
                return "image/gif";
            }
        }
        if (data.length >= 12
                && "RIFF".equals(new String(data, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(data, 8, 4, StandardCharsets.US_ASCII))) {
            return "image/webp";
        }
        return null;
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }
}
