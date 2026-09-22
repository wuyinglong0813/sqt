package com.tradepass.module.identity.service.fadada;

import com.tradepass.module.identity.service.certification.CompanyCertificationService;
import com.tradepass.module.identity.service.permission.AccessControlService;
import static com.tradepass.module.identity.api.fadada.FadadaCompanyOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.api.fadada.dto.FadadaCompanyIdentityRespDTO;
import com.tradepass.module.identity.api.fadada.dto.LegalRepresentativeRespDTO;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpSealDO;
import com.tradepass.framework.fadada.core.FadadaCompanyGateway;
import com.fasc.open.api.enums.corp.OperatorTypeEnum;
import com.tradepass.module.identity.service.certification.CompanyCertificationService.CertifiedApplicantRole;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpSealMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public interface FadadaCompanyService {
    FadadaCompanyIdentityRespDTO current(long companyId);
    ServiceUrlPayload createAuthUrl(long companyId);
    FadadaCompanyIdentityRespDTO syncCurrent(long companyId);
    ServiceUrlPayload createLegalRepresentativeUrl(long companyId);
    LegalRepresentativeRespDTO syncLegalRepresentative(long companyId);
    FadadaCompanyIdentityRespDTO sync(long companyId);
    FadadaCompanyIdentityRespDTO syncByClientCorpId(String clientCorpId);
    FadadaCompanyIdentityRespDTO syncByOpenCorpId(String openCorpId);
    FadadaCompanyIdentityRespDTO syncCallback(String clientCorpId, String openCorpId, JsonNode data);
    ServiceUrlPayload createSealManageUrl(long companyId);
    FadadaCorpIdentityDO requireVerified(long companyId);
    String enabledSealId(long companyId);
}
