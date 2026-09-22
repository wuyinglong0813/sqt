package com.tradepass.framework.audit.core;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.audit.core.AuditLog;
import com.tradepass.framework.audit.core.AuditLogMapper;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {
    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void log(long companyId, String bizType, Object bizId, String action, String detail) {
        logAs(companyId, AuthContext.userId(), bizType, bizId, action, detail);
    }

    public void logAs(long companyId, long userId, String bizType, Object bizId, String action, String detail) {
        AuditLog audit = new AuditLog();
        audit.setCompanyId(companyId);
        audit.setUserId(userId);
        audit.setBizType(bizType);
        audit.setBizId(String.valueOf(bizId));
        audit.setAction(action);
        audit.setDetail(detail);
        auditLogMapper.insert(audit);
    }
}
