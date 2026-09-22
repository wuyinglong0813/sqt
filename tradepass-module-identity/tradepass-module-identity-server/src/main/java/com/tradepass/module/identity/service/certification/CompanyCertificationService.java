package com.tradepass.module.identity.service.certification;

import com.tradepass.module.identity.service.company.TenantBootstrapService;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.framework.audit.core.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.controller.app.certification.vo.CertificationReviewReqVO;
import com.tradepass.module.identity.api.certification.dto.CertificationApplicationRespDTO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.certification.CompanyCertificationApplicationDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.mysql.certification.CompanyCertificationApplicationMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface CompanyCertificationService {
    public enum CertifiedApplicantRole { LEGAL, ADMIN }

    CertificationApplicationRespDTO submit(long companyId);
    CertificationApplicationRespDTO submit(String companyId);
    List<CertificationApplicationRespDTO> myApplications();
    void completeProviderCertification(long companyId, long applicantUserId, String providerRequestId, String reason, CertifiedApplicantRole role);
    void requireLegalClaim(long companyId, long userId);
    void completeLegalClaim(long companyId, long userId, String providerRequestId);
    CertificationApplicationRespDTO review(String suppliedToken, CertificationReviewReqVO request);
}
