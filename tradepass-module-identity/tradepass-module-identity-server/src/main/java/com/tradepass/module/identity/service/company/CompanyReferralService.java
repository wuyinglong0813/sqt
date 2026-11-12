package com.tradepass.module.identity.service.company;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyInviteDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyOnboardingAttemptDO;
import com.tradepass.module.identity.dal.dataobject.counterparty.CounterpartyRelationEntityDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyInviteMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyReferralMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

@Service
public class CompanyReferralService {
    private static final Logger log = LoggerFactory.getLogger(CompanyReferralService.class);
    private final CompanyInviteMapper invites;
    private final CompanyMapper companies;
    private final CompanyReferralMapper referrals;
    private final CounterpartyRelationMapper relations;

    public CompanyReferralService(CompanyInviteMapper invites, CompanyMapper companies,
            CompanyReferralMapper referrals, CounterpartyRelationMapper relations) {
        this.invites = invites;
        this.companies = companies;
        this.referrals = referrals;
        this.relations = relations;
    }

    Long eligibleInviteId(CompanyDO invited, String code) {
        if (code == null || code.isBlank()) return null;
        CompanyInviteDO invite = invites.selectOne(new LambdaQueryWrapper<CompanyInviteDO>()
                .eq(CompanyInviteDO::getCode, code.trim())
                .eq(CompanyInviteDO::getType, "counterparty")
                .eq(CompanyInviteDO::getUsed, false)
                .gt(CompanyInviteDO::getExpiresAt, LocalDateTime.now())
                .last("LIMIT 1"));
        if (invite == null || eligibleInviter(invite, invited) == null) return null;
        return invite.getId();
    }

    // Runs in the first successful onboarding transaction, under the invited company's row lock.
    void confirm(CompanyDO invited, CompanyOnboardingAttemptDO attempt) {
        if (!"VERIFIED".equals(invited.getCertificationStatus()) || attempt.getReferralInviteId() == null) return;
        CompanyInviteDO invite = invites.selectOne(new LambdaQueryWrapper<CompanyInviteDO>()
                .eq(CompanyInviteDO::getId, attempt.getReferralInviteId())
                .eq(CompanyInviteDO::getType, "counterparty")
                .eq(CompanyInviteDO::getUsed, false)
                .last("LIMIT 1 FOR UPDATE"));
        if (invite == null) return;
        CompanyDO inviter = eligibleInviter(invite, invited);
        if (inviter == null) return;
        try {
            referrals.insertConfirmed(invite.getId(), invited.getId(), subject(invited),
                    inviter.getId(), subject(inviter));
        } catch (DuplicateKeyException alreadyRewarded) {
            return;
        }
        // The code was valid when attached to this attempt. A later expiration does not
        // invalidate the attribution, but an earlier acceptance or revocation wins.
        invites.update(new LambdaUpdateWrapper<CompanyInviteDO>()
                .eq(CompanyInviteDO::getId, invite.getId())
                .set(CompanyInviteDO::getUsed, true)
                .set(CompanyInviteDO::getUsedBy, attempt.getApplicantUserId()));
        try {
            relations.insert(cooperation(invite, invited.getId(), invited, inviter));
        } catch (DuplicateKeyException nameAlreadyInUse) {
            // The legacy relation key uses the company name. A collision cannot undo certification.
            log.info("Cooperation relation name is already in use for company invitation {}", invite.getId());
        }
    }

    private CompanyDO eligibleInviter(CompanyInviteDO invite, CompanyDO invited) {
        if (Objects.equals(invite.getCompanyId(), invited.getId())) return null;
        CompanyDO inviter = companies.selectById(invite.getCompanyId());
        if (inviter == null || subject(inviter).isEmpty() || subject(invited).isEmpty()
                || subject(inviter).equals(subject(invited))) return null;
        return inviter;
    }

    private String subject(CompanyDO company) {
        return company.getCreditCode() == null ? "" : company.getCreditCode().trim().toUpperCase(Locale.ROOT);
    }

    static CounterpartyRelationEntityDO cooperation(CompanyInviteDO invite, long acceptingCompanyId,
            CompanyDO acceptingCompany, CompanyDO invitingCompany) {
        CounterpartyRelationEntityDO relation = new CounterpartyRelationEntityDO();
        if ("supplier".equalsIgnoreCase(invite.getRelationRole())) {
            relation.setCompanyId(acceptingCompanyId);
            relation.setCounterpartyCompanyId(invite.getCompanyId());
            relation.setCounterpartyCompanyName(invitingCompany == null ? "未知企业" : invitingCompany.getName());
        } else {
            relation.setCompanyId(invite.getCompanyId());
            relation.setCounterpartyCompanyId(acceptingCompanyId);
            relation.setCounterpartyCompanyName(acceptingCompany == null ? "未知企业" : acceptingCompany.getName());
        }
        relation.setRelationType("SUPPLIER");
        relation.setStatus("ACTIVE");
        return relation;
    }
}
