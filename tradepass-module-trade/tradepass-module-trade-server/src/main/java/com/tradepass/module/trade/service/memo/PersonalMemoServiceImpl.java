package com.tradepass.module.trade.service.memo;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.tradepass.framework.audit.core.AuditLogService;

import com.tradepass.framework.mybatis.core.ApplicationIds;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PersonalMemoServiceImpl implements PersonalMemoService {
    public static final String CONTRACT = "CONTRACT";
    public static final String SALES_ORDER = "SALES_ORDER";
    public static final String RETURN_ORDER = "RETURN_ORDER";

    private final JdbcTemplate jdbc;
    private final ContractReader contractMapper;
    private final BusinessDocumentMapper documentMapper;
    private final AccessControlOperations accessControlService;
    private final AuditLogService auditLogService;

    public PersonalMemoServiceImpl(JdbcTemplate jdbc,
                               ContractReader contractMapper,
                               BusinessDocumentMapper documentMapper,
                               AccessControlOperations accessControlService,
                               AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.contractMapper = contractMapper;
        this.documentMapper = documentMapper;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
    }

    public Map<String, Object> get(String type, Long bizId) {
        AccessTarget target = requireTarget(type, bizId);
        List<Map<String, Object>> rows = jdbc.query("""
                        SELECT content, updated_at FROM business_memo
                        WHERE company_id = ? AND user_id = ? AND biz_type = ? AND biz_id = ?
                        """, (rs, rowNum) -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("content", rs.getString("content"));
                    view.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
                    return view;
                }, target.companyId(), AuthContext.userId(), target.type(), bizId);
        if (!rows.isEmpty()) return rows.get(0);
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("content", "");
        empty.put("updatedAt", null);
        return empty;
    }

    @Transactional
    public Map<String, Object> save(String type, Long bizId, String content) {
        AccessTarget target = requireTarget(type, bizId);
        if ("COMPLETED".equals(target.contractStatus()) || "VOIDED".equals(target.contractStatus())) {
            throw new BusinessException("合同已结束或作废，备忘录仅允许查看");
        }
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(1) FROM bilateral_action_request
                WHERE contract_id = ? AND status = 'PENDING'
                """, Long.class, target.contractId());
        if (pending != null && pending > 0) throw new BusinessException("合同正在等待双方处理，备忘录暂不可修改");
        String value = content == null ? "" : content.trim();
        if (value.length() > 4000) throw new BusinessException("备忘录不能超过 4000 字");
        jdbc.update("""
                INSERT INTO business_memo (id, company_id, user_id, biz_type, biz_id, content)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE content = VALUES(content), updated_at = CURRENT_TIMESTAMP
                """, ApplicationIds.next(), target.companyId(), AuthContext.userId(), target.type(), bizId, value);
        auditLogService.log(target.companyId(), "PERSONAL_MEMO", target.type() + ":" + bizId,
                "UPDATE", "更新个人进展备忘录");
        return get(target.type(), bizId);
    }

    private AccessTarget requireTarget(String type, Long bizId) {
        long companyId = AuthContext.requireCompanyId();
        accessControlService.requireAnyPermission(companyId,
                "contract_view", "contract_sign", "order_create", "sales_order_receive");
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (CONTRACT.equals(normalized)) {
            TradeContractRespDTO contract = contractMapper.selectById(bizId);
            requireContractParty(contract, companyId);
            return new AccessTarget(companyId, CONTRACT, contract.getId(), contract.getStatus());
        }
        if (SALES_ORDER.equals(normalized) || RETURN_ORDER.equals(normalized)) {
            BusinessDocumentDO document = documentMapper.selectById(bizId);
            if (document == null || (!SALES_ORDER.equals(document.getDocumentType())
                    && !RETURN_ORDER.equals(document.getDocumentType()))) {
                throw new BusinessException("销售单不存在");
            }
            TradeContractRespDTO contract = contractMapper.selectById(document.getContractId());
            requireContractParty(contract, companyId);
            if ("DRAFT".equals(document.getStatus())
                    && !Long.valueOf(companyId).equals(document.getCompanyId())) {
                throw new BusinessException("销售单不存在");
            }
            return new AccessTarget(companyId, document.getDocumentType(), contract.getId(), contract.getStatus());
        }
        throw new BusinessException("备忘录类型不正确");
    }

    private void requireContractParty(TradeContractRespDTO contract, long companyId) {
        if (contract == null || (!Long.valueOf(companyId).equals(contract.getCompanyId())
                && !Long.valueOf(companyId).equals(contract.getCounterpartyCompanyId()))) {
            throw new BusinessException("合同不存在");
        }
    }

    private record AccessTarget(long companyId, String type, Long contractId, String contractStatus) {
    }
}
