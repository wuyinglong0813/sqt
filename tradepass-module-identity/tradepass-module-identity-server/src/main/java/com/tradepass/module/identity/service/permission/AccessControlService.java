package com.tradepass.module.identity.service.permission;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.TradePassDtos.MemberRole;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

public interface AccessControlService {
    boolean isActiveMember(long companyId, long userId);
    Long resolveCompanyId(String companyId);
    void requireManager(long companyId);
    void requireLegal(long companyId);
    void requireMemberOrClaim(long companyId);
    CompanyProfileAccess requireCompanyProfileAccess(long targetCompanyId);
    void requireLegalOrClaim(long companyId);
    void requireCertificationOperator(long companyId);
    void requirePermission(long companyId, String permission);
    void requireAnyPermission(long companyId, String... permissions);
    boolean hasPermission(long companyId, String permission);
    EffectiveRole effectiveRole(long companyId, long userId);
}
