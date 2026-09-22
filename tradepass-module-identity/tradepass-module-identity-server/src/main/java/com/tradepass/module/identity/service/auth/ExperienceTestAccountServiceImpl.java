package com.tradepass.module.identity.service.auth;

import com.tradepass.module.identity.service.company.TenantBootstrapService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.counterparty.CounterpartyRelationEntityDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 体验版专用测试账号初始化。仅命中明确配置的测试手机号时执行，关闭开关后完全失效。
 */
@Service
public class ExperienceTestAccountServiceImpl implements ExperienceTestAccountService {
    private static final CompanySeed HEBEI_COMPANY = new CompanySeed(
            "河北光屿行贸易有限公司",
            "91130100MA00000001",
            "满帅",
            "河北省石家庄市",
            "15632287507"
    );
    private static final CompanySeed SHANGHAI_COMPANY = new CompanySeed(
            "上海远航进出口有限公司",
            "91310000MA00000002",
            "王海",
            "上海市浦东新区",
            "19802166615"
    );
    private static final Map<String, CompanySeed> PHONE_COMPANIES = Map.of(
            "15632287507", HEBEI_COMPANY,
            "19802166615", SHANGHAI_COMPANY
    );

    private final CompanyMapper companyMapper;
    private final CompanyMemberMapper companyMemberMapper;
    private final CounterpartyRelationMapper counterpartyRelationMapper;
    private final TenantBootstrapService tenantBootstrapService;
    private final boolean enabled;

    public ExperienceTestAccountServiceImpl(CompanyMapper companyMapper,
                                        CompanyMemberMapper companyMemberMapper,
                                        CounterpartyRelationMapper counterpartyRelationMapper,
                                        TenantBootstrapService tenantBootstrapService,
                                        @Value("${tradepass.experience-test-accounts.enabled:false}") boolean enabled) {
        this.companyMapper = companyMapper;
        this.companyMemberMapper = companyMemberMapper;
        this.counterpartyRelationMapper = counterpartyRelationMapper;
        this.tenantBootstrapService = tenantBootstrapService;
        this.enabled = enabled;
    }

    public Long provisionIfConfigured(SysUserDO user, String verifiedPhone) {
        if (!enabled || user == null || user.getId() == null || verifiedPhone == null) {
            return null;
        }
        CompanySeed assignedSeed = PHONE_COMPANIES.get(verifiedPhone.trim());
        if (assignedSeed == null) {
            return null;
        }

        CompanyDO hebei = ensureCompany(HEBEI_COMPANY, user.getId());
        CompanyDO shanghai = ensureCompany(SHANGHAI_COMPANY, user.getId());
        CompanyDO assigned = assignedSeed == HEBEI_COMPANY ? hebei : shanghai;

        ensureLegalMembership(assigned.getId(), user.getId());
        tenantBootstrapService.initialize(hebei.getId(), user.getId());
        tenantBootstrapService.initialize(shanghai.getId(), user.getId());
        ensureRelation(hebei, shanghai);
        ensureRelation(shanghai, hebei);
        return assigned.getId();
    }

    private CompanyDO ensureCompany(CompanySeed seed, long operatorUserId) {
        CompanyDO company = companyMapper.selectOne(new LambdaQueryWrapper<CompanyDO>()
                .eq(CompanyDO::getCreditCode, seed.creditCode())
                .last("LIMIT 1"));
        if (company == null) {
            company = new CompanyDO();
            company.setName(seed.name());
            company.setCreditCode(seed.creditCode());
            company.setLegalPersonName(seed.legalPersonName());
            company.setRegisteredAddress(seed.registeredAddress());
            company.setContactPhone(seed.contactPhone());
            company.setCreatedBy(operatorUserId);
            markReadyForExperience(company);
            companyMapper.insert(company);
            return company;
        }

        company.setName(seed.name());
        company.setLegalPersonName(seed.legalPersonName());
        company.setRegisteredAddress(seed.registeredAddress());
        company.setContactPhone(seed.contactPhone());
        if (company.getCreatedBy() == null) {
            company.setCreatedBy(operatorUserId);
        }
        markReadyForExperience(company);
        companyMapper.updateById(company);
        return company;
    }

    private void markReadyForExperience(CompanyDO company) {
        company.setCertificationStatus("VERIFIED");
        company.setRealNameStatus("VERIFIED");
        company.setFaceStatus("VERIFIED");
        company.setSealStatus("UPLOADED");
    }

    private void ensureLegalMembership(long companyId, long userId) {
        CompanyMemberDO member = companyMemberMapper.selectOne(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId)
                .eq(CompanyMemberDO::getUserId, userId)
                .last("LIMIT 1"));
        if (member == null) {
            member = new CompanyMemberDO();
            member.setCompanyId(companyId);
            member.setUserId(userId);
            member.setRoleCode("LEGAL");
            member.setIsLegalPerson(true);
            member.setIsAdministrator(false);
            member.setStatus("ACTIVE");
            companyMemberMapper.insert(member);
            return;
        }
        member.setRoleCode("LEGAL");
        member.setIsLegalPerson(true);
        member.setIsAdministrator(false);
        member.setStatus("ACTIVE");
        companyMemberMapper.updateById(member);
    }

    private void ensureRelation(CompanyDO company, CompanyDO counterparty) {
        CounterpartyRelationEntityDO relation = counterpartyRelationMapper.selectOne(
                new LambdaQueryWrapper<CounterpartyRelationEntityDO>()
                        .eq(CounterpartyRelationEntityDO::getCompanyId, company.getId())
                        .eq(CounterpartyRelationEntityDO::getCounterpartyCompanyName, counterparty.getName())
                        .last("LIMIT 1"));
        if (relation == null) {
            relation = new CounterpartyRelationEntityDO();
            relation.setCompanyId(company.getId());
            relation.setCounterpartyCompanyId(counterparty.getId());
            relation.setCounterpartyCompanyName(counterparty.getName());
            relation.setRelationType("SUPPLIER");
            relation.setStatus("ACTIVE");
            counterpartyRelationMapper.insert(relation);
            return;
        }
        relation.setCounterpartyCompanyId(counterparty.getId());
        relation.setRelationType("SUPPLIER");
        relation.setStatus("ACTIVE");
        counterpartyRelationMapper.updateById(relation);
    }

    private record CompanySeed(String name,
                               String creditCode,
                               String legalPersonName,
                               String registeredAddress,
                               String contactPhone) {
    }
}
