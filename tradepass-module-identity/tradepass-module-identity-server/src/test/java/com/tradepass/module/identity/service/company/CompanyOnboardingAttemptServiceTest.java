package com.tradepass.module.identity.service.company;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.controller.app.company.vo.CompanySubmitReqVO;
import com.tradepass.module.identity.dal.dataobject.certification.CompanyCertificationApplicationDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyOnboardingAttemptDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.dal.mysql.certification.CompanyCertificationApplicationMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyOnboardingAttemptMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompanyOnboardingAttemptServiceTest {
    private CompanyMapper companies;
    private CompanyOnboardingAttemptMapper attempts;
    private CompanyMemberMapper members;
    private FadadaCorpIdentityMapper identities;
    private CompanyCertificationApplicationMapper applications;
    private FadadaPersonalIdentityService personal;
    private CompanyOnboardingAttemptService service;
    private CompanyDO company;

    @BeforeEach
    void setup() {
        MybatisTestSupport.initialize(CompanyDO.class, CompanyOnboardingAttemptDO.class, CompanyMemberDO.class,
                FadadaCorpIdentityDO.class, CompanyCertificationApplicationDO.class);
        companies = mock(CompanyMapper.class); attempts = mock(CompanyOnboardingAttemptMapper.class);
        members = mock(CompanyMemberMapper.class); identities = mock(FadadaCorpIdentityMapper.class);
        applications = mock(CompanyCertificationApplicationMapper.class); personal = mock(FadadaPersonalIdentityService.class);
        service = new CompanyOnboardingAttemptService(companies, attempts, members, identities, applications, personal);
        company = new CompanyDO(); company.setId(3L); company.setCreatedBy(8L); company.setName("原草稿");
        company.setCreditCode("CODE3"); company.setLegalPersonName("原申报法人"); company.setBankAccount("ORIGINAL-BANK");
        company.setCertificationStatus("PENDING"); when(companies.selectByIdForUpdate(3L)).thenReturn(company);
        AuthContext.set(7L, null);
    }

    @AfterEach void cleanup() { AuthContext.clear(); }

    @Test
    void eachHistoricalEstablishmentSignalBlocksANewApplication() {
        company.setCertificationStatus("VERIFIED"); assertThat(service.isEstablished(company)).isTrue();
        company.setCertificationStatus("REJECTED");
        when(members.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThat(service.isEstablished(company)).isTrue();
        when(members.selectCount(any(Wrapper.class))).thenReturn(0L);
        FadadaCorpIdentityDO identity = new FadadaCorpIdentityDO(); identity.setLocalStatus("VERIFIED");
        when(identities.selectOne(any(Wrapper.class))).thenReturn(identity);
        assertThat(service.isEstablished(company)).isTrue();
        identity.setLocalStatus("FAILED"); identity.setVerifiedAt(LocalDateTime.now().minusDays(3));
        assertThat(service.isEstablished(company)).isTrue();
        identity.setVerifiedAt(null);
        when(applications.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThat(service.isEstablished(company)).isTrue();
        when(applications.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertThat(service.isEstablished(company)).isFalse();
    }

    @Test
    void independentDraftRequiresPersonalProofAndDoesNotCopyOtherApplicantsBankDetails() {
        PersonalIdentityRespDTO person = mock(PersonalIdentityRespDTO.class); when(person.status()).thenReturn("VERIFIED");
        when(personal.requireCurrentVerified()).thenReturn(person);
        doAnswer(invocation -> { ((CompanyOnboardingAttemptDO) invocation.getArgument(0)).setId(20L); return 1; })
                .when(attempts).insert(any(CompanyOnboardingAttemptDO.class));
        var draft = service.draft(3L, 7L, new CompanySubmitReqVO(null, "本人申报名称", "CODE3", "本人申报法人"));
        assertThat(draft.getApplicantUserId()).isEqualTo(7L); assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getBankAccount()).isNull(); assertThat(draft.getProviderRequestId()).startsWith("FDD-ATT-");
        assertThat(company.getName()).isEqualTo("原草稿"); assertThat(company.getCreatedBy()).isEqualTo(8L);
        assertThat(company.getBankAccount()).isEqualTo("ORIGINAL-BANK");
        verify(companies, never()).updateById(any(CompanyDO.class));
        verify(members, never()).insert(any(CompanyMemberDO.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatingADraftExplicitlyClearsOptionalSnapshotFields() {
        PersonalIdentityRespDTO person = mock(PersonalIdentityRespDTO.class); when(person.status()).thenReturn("VERIFIED");
        when(personal.requireCurrentVerified()).thenReturn(person);
        CompanyOnboardingAttemptDO draft = active(); draft.setStatus("DRAFT");
        draft.setRegisteredAddress("OLD-ADDRESS"); draft.setContactPhone("OLD-CONTACT");
        draft.setBankName("OLD-BANK"); draft.setBankAccount("OLD-ACCOUNT");
        when(attempts.selectOne(any(Wrapper.class))).thenReturn(draft);
        when(attempts.update(any(Wrapper.class))).thenReturn(1);

        CompanyOnboardingAttemptDO result = service.draft(3L, 7L,
                new CompanySubmitReqVO(null, "本人申报名称", "CODE3", "本人申报法人"));

        ArgumentCaptor<Wrapper<CompanyOnboardingAttemptDO>> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(attempts).update(update.capture());
        LambdaUpdateWrapper<CompanyOnboardingAttemptDO> values = (LambdaUpdateWrapper<CompanyOnboardingAttemptDO>) update.getValue();
        assertThat(values.getSqlSet()).contains("registered_address", "contact_phone", "bank_name", "bank_account");
        assertThat(values.getParamNameValuePairs().values()).containsNull();
        assertThat(values.getParamNameValuePairs().values().stream().filter(java.util.Objects::isNull).count()).isEqualTo(4);
        assertThat(result.getBankAccount()).isNull(); assertThat(result.getBankName()).isNull();
        assertThat(result.getRegisteredAddress()).isNull(); assertThat(result.getContactPhone()).isNull();
        verify(attempts, never()).updateById(any(CompanyOnboardingAttemptDO.class));
        assertThat(company.getBankAccount()).isEqualTo("ORIGINAL-BANK");
    }

    @Test
    @SuppressWarnings("unchecked")
    void completionSupersedesOtherOpenAttemptsAndOnlyRemovesPendingCandidates() {
        FadadaCorpIdentityDO identity = new FadadaCorpIdentityDO(); identity.setCompanyId(3L); identity.setOnboardingAttemptId(20L);
        when(identities.selectOne(any(Wrapper.class))).thenReturn(identity);
        CompanyOnboardingAttemptDO attempt = active(); attempt.setBankAccount("WINNER-BANK");
        when(attempts.selectOne(any(Wrapper.class))).thenReturn(attempt);
        service.complete(3L, 7L, "REQUEST-20");
        assertThat(attempt.getStatus()).isEqualTo("COMPLETED");
        ArgumentCaptor<Wrapper<CompanyDO>> published = ArgumentCaptor.forClass(Wrapper.class);
        verify(companies).update(published.capture());
        LambdaUpdateWrapper<CompanyDO> publication = (LambdaUpdateWrapper<CompanyDO>) published.getValue();
        assertThat(publication.getSqlSet()).contains("bank_account", "registered_address").doesNotContain("legal_person_name");
        assertThat(publication.getParamNameValuePairs().values()).contains("WINNER-BANK");
        ArgumentCaptor<Wrapper<CompanyOnboardingAttemptDO>> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(attempts).update(update.capture());
        LambdaUpdateWrapper<CompanyOnboardingAttemptDO> values = (LambdaUpdateWrapper<CompanyOnboardingAttemptDO>) update.getValue();
        values.getSqlSegment(); values.getSqlSet();
        assertThat(values.getParamNameValuePairs().values()).contains("SUPERSEDED", "DRAFT", "ACTIVE", 20L, 3L);
        ArgumentCaptor<Wrapper<CompanyMemberDO>> deleted = ArgumentCaptor.forClass(Wrapper.class);
        verify(members).delete(deleted.capture()); deleted.getValue().getSqlSegment();
        assertThat(deleted.getValue().getCustomSqlSegment()).contains("user_id <>", "status", "role_code");
    }

    @Test
    void anExpiredAttemptCannotBeCompletedByItsOldApplicant() {
        FadadaCorpIdentityDO identity = new FadadaCorpIdentityDO(); identity.setCompanyId(3L); identity.setOnboardingAttemptId(20L);
        when(identities.selectOne(any(Wrapper.class))).thenReturn(identity);
        CompanyOnboardingAttemptDO attempt = active(); attempt.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(attempts.selectOne(any(Wrapper.class))).thenReturn(attempt);
        assertThatThrownBy(() -> service.complete(3L, 7L, "REQUEST-20")).hasMessageContaining("已失效");
        verify(attempts, never()).updateById(any(CompanyOnboardingAttemptDO.class));
    }

    @Test
    void cancellingAnOwnDraftCannotCancelAnotherPersonsCurrentFlow() {
        FadadaCorpIdentityDO identity = new FadadaCorpIdentityDO(); identity.setId(9L); identity.setOnboardingAttemptId(21L);
        when(identities.selectOne(any(Wrapper.class))).thenReturn(identity);
        CompanyOnboardingAttemptDO own = active(); own.setStatus("DRAFT");
        when(attempts.selectOne(any(Wrapper.class))).thenReturn(own);
        service.cancel(3L, 7L);
        assertThat(own.getStatus()).isEqualTo("CANCELLED");
        verify(identities, never()).update(any(Wrapper.class)); verify(companies, never()).update(any(Wrapper.class));
    }

    private CompanyOnboardingAttemptDO active() {
        CompanyOnboardingAttemptDO attempt = new CompanyOnboardingAttemptDO(); attempt.setId(20L); attempt.setCompanyId(3L);
        attempt.setApplicantUserId(7L); attempt.setStatus("ACTIVE"); attempt.setProviderRequestId("REQUEST-20");
        attempt.setExpiresAt(LocalDateTime.now().plusMinutes(15)); return attempt;
    }
}
