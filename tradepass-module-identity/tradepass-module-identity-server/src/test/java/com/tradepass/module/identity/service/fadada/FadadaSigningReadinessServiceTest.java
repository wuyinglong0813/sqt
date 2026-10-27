package com.tradepass.module.identity.service.fadada;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations.EffectiveRole;
import com.tradepass.module.identity.controller.app.fadada.FadadaIdentityController;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaSigningMemberSyncDO;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaSigningMemberSyncMapper;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FadadaSigningReadinessServiceTest {
    @AfterEach void clearContext() { AuthContext.clear(); }

    @Test void anonymousAndInactiveMembersCannotReadPreparationState() {
        var f = new Fixture();
        AuthContext.clear();
        assertThatThrownBy(() -> f.service.current(3L)).hasMessageContaining("未登录");
        verifyNoInteractions(f.access, f.personal, f.identities, f.queue);
        AuthContext.set(7L, 3L);
        when(f.access.isActiveMember(3L, 7L)).thenReturn(false);
        assertThatThrownBy(() -> f.service.current(3L)).hasMessageContaining("不是企业的有效成员");
        verifyNoInteractions(f.personal, f.identities, f.queue);
    }

    @Test void aRoleLabelWithoutEffectiveSigningPermissionDoesNotCreateATodo() {
        var f = new Fixture();
        f.permissions("company_manage", "member_manage");
        assertThat(f.service.current(3L).status()).isEqualTo("NOT_REQUIRED");
        verifyNoInteractions(f.personal, f.identities, f.queue);
    }

    @Test void disabledProviderDoesNotPromptPeopleToPerformUnavailableSetup() {
        var f = new Fixture(); f.properties.setEnabled(false);
        assertThat(f.service.current(3L).status()).isEqualTo("NOT_REQUIRED");
        verifyNoInteractions(f.personal, f.identities, f.queue);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_STARTED", "IN_PROGRESS", "FAILED"})
    void personalIdentityTakesPriorityOverAnyCachedReadyProof(String status) {
        var f = new Fixture();
        when(f.personal.current()).thenReturn(f.person(status, "authorized"));
        var result = f.service.current(3L);
        assertThat(result.status()).isEqualTo("WAITING_PERSONAL_IDENTITY");
        assertThat(result.action()).isEqualTo("personal");
        verifyNoInteractions(f.identities, f.queue);
    }

    @Test void revokedPersonalAuthorizationCannotBeHiddenByACachedVerifiedStatus() {
        var f = new Fixture();
        when(f.personal.current()).thenReturn(f.person("VERIFIED", "unauthorized"));
        assertThat(f.service.current(3L).status()).isEqualTo("WAITING_PERSONAL_IDENTITY");
        verifyNoInteractions(f.identities, f.queue);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "{}", "not-json", "[\"ident_info\"]", "[1]"})
    void missingOrMalformedCompanyScopesCannotBeReportedAsReady(String scopes) {
        var f = new Fixture(); f.company.setAuthScopes(scopes);
        assertThat(f.service.current(3L).status()).isEqualTo("WAITING_COMPANY_AUTHORIZATION");
        assertThat(f.service.current(3L).action()).isEqualTo("none");
        verifyNoInteractions(f.queue);
    }

    @Test void revokedCompanyAuthorizationOverridesTheQueueAndOnlyLocalCompanyManagersGetAnAction() {
        var f = new Fixture(); f.company.setBindingStatus("unauthorized");
        f.permissions("contract_sign", "company_manage");
        var result = f.service.current(3L);
        assertThat(result.status()).isEqualTo("WAITING_COMPANY_AUTHORIZATION");
        assertThat(result.action()).isEqualTo("company");
        verifyNoInteractions(f.queue);
    }

    @ParameterizedTest
    @CsvSource({
            "SIGNING_READY,READY,none", "READY,PENDING,none", "WAITING_PERSONAL_IDENTITY,PENDING,none",
            "PENDING,PENDING,none", "SYNCING,SYNCING,none", "ACTIVATION_REQUIRED,ACTIVATION_REQUIRED,member",
            "WAITING_COMPANY_AUTHORIZATION,WAITING_COMPANY_AUTHORIZATION,none",
            "WAITING_SEAL_AUTHORIZATION,WAITING_SEAL_AUTHORIZATION,none",
            "WAITING_SEAL_CONFIGURATION,ADMIN_REVIEW_REQUIRED,none",
            "ADMIN_REVIEW_REQUIRED,ADMIN_REVIEW_REQUIRED,recheck", "UNKNOWN,ADMIN_REVIEW_REQUIRED,recheck"})
    void existingQueueStatesAreReadAsHintsWithoutInvitationsOrOtherMutations(String queueStatus, String status, String action) {
        var f = new Fixture(); f.work.setStatus(queueStatus);
        var result = f.service.current(3L);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.action()).isEqualTo(action);
        verify(f.personal).current();
        verify(f.identities).selectOne(any(Wrapper.class));
        verify(f.queue).selectOne(any(Wrapper.class));
        verifyNoMoreInteractions(f.personal, f.identities, f.queue);
    }

    @Test void aMissingQueueRowWaitsForAutomaticBackfillWithoutEnqueueing() {
        var f = new Fixture(); when(f.queue.selectOne(any(Wrapper.class))).thenReturn(null);
        var result = f.service.current(3L);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.action()).isEqualTo("none");
        verify(f.queue).selectOne(any(Wrapper.class));
        verifyNoMoreInteractions(f.queue);
    }

    @Test void aFailedQueueAttemptOffersOnlyAnExplicitRecheckAndDoesNotTrustAReadyLabel() {
        var f = new Fixture(); f.work.setStatus("PENDING"); f.work.setLastErrorCode(500);
        assertThat(f.service.current(3L).action()).isEqualTo("recheck");
        f.work.setStatus("SIGNING_READY");
        assertThat(f.service.current(3L).status()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @CsvSource({"true,true,true", "true,false,false", "false,true,false", "false,false,false"})
    void sealGrantActionRequiresBothLocalMemberManagementAndSealPermission(boolean manager, boolean seal, boolean expected) {
        var f = new Fixture();
        var permissions = new ArrayList<>(List.of("contract_sign"));
        if (manager) permissions.add("auth_manage");
        if (seal) permissions.add("seal_manage");
        f.permissions(permissions.toArray(String[]::new));
        f.work.setStatus("WAITING_SEAL_AUTHORIZATION");
        var result = f.service.current(3L);
        assertThat(result.canManageSealAuthorization()).isEqualTo(expected);
        assertThat(result.action()).isEqualTo(expected ? "sealGrant" : "none");
        if (expected) assertThat(result.message()).contains("实际办理权限由电子签平台核验");
    }

    @Test void allPermissionIncludesTheLocalManagementActionButDoesNotClaimProviderManagementRights() {
        var f = new Fixture(); f.permissions("all"); f.work.setStatus("WAITING_SEAL_AUTHORIZATION");
        assertThat(f.service.current(3L).action()).isEqualTo("sealGrant");
        assertThat(f.service.current(3L).message()).contains("电子签平台核验");
    }

    @Test void httpEndpointReadsOnlyTheAuthenticatedPersonsExactCompanyAndReturnsNoProviderLink() throws Exception {
        var f = new Fixture(); f.work.setStatus("ACTIVATION_REQUIRED");
        var controller = new FadadaIdentityController(f.personal, mock(FadadaCompanyService.class),
                mock(FadadaSigningMemberService.class), mock(FadadaSigningAuthorizationService.class), f.service);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/fadada/companies/3/signing-readiness").param("targetUserId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVATION_REQUIRED"))
                .andExpect(jsonPath("$.data.action").value("member"))
                .andExpect(jsonPath("$.data.canManageSealAuthorization").value(false))
                .andExpect(jsonPath("$.data.url").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist());
        verify(f.access).isActiveMember(3L, 7L);
        verify(f.queue).selectOne(argThat((Wrapper<FadadaSigningMemberSyncDO> value) -> {
            var query = (LambdaQueryWrapper<FadadaSigningMemberSyncDO>) value;
            assertThat(query.getSqlSegment()).contains("company_id", "user_id");
            assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder(3L, 7L);
            return true;
        }));
        verify(f.identities).selectOne(argThat((Wrapper<FadadaCorpIdentityDO> value) -> {
            var query = (LambdaQueryWrapper<FadadaCorpIdentityDO>) value;
            assertThat(query.getSqlSegment()).contains("company_id");
            assertThat(query.getParamNameValuePairs().values()).containsExactly(3L);
            return true;
        }));
        verifyNoMoreInteractions(f.queue, f.identities);
    }

    private static class Fixture {
        final AccessControlService access = mock(AccessControlService.class);
        final FadadaPersonalIdentityService personal = mock(FadadaPersonalIdentityService.class);
        final FadadaCorpIdentityMapper identities = mock(FadadaCorpIdentityMapper.class);
        final FadadaSigningMemberSyncMapper queue = mock(FadadaSigningMemberSyncMapper.class);
        final FadadaProperties properties = new FadadaProperties();
        final FadadaCorpIdentityDO company = new FadadaCorpIdentityDO();
        final FadadaSigningMemberSyncDO work = new FadadaSigningMemberSyncDO();
        final FadadaSigningReadinessService service;
        Fixture() {
            MybatisTestSupport.initialize(FadadaCorpIdentityDO.class, FadadaSigningMemberSyncDO.class);
            AuthContext.set(7L, 3L);
            properties.setEnabled(true);
            when(access.isActiveMember(3L, 7L)).thenReturn(true);
            permissions("contract_sign");
            when(personal.current()).thenReturn(person("VERIFIED", "authorized"));
            company.setCompanyId(3L); company.setLocalStatus("VERIFIED"); company.setIdentStatus("identified");
            company.setBindingStatus("authorized"); company.setOpenCorpId("corp-3");
            company.setAuthScopes("[\"ident_info\",\"seal_info\",\"signtask_init\",\"signtask_info\",\"signtask_file\",\"organization\"]");
            when(identities.selectOne(any(Wrapper.class))).thenReturn(company);
            work.setCompanyId(3L); work.setUserId(7L); work.setStatus("SIGNING_READY"); work.setLastErrorCode(0);
            when(queue.selectOne(any(Wrapper.class))).thenReturn(work);
            service = new FadadaSigningReadinessService(access, personal, identities, queue, properties, new ObjectMapper());
        }
        void permissions(String... values) {
            when(access.effectiveRole(3L, 7L)).thenReturn(new EffectiveRole("ADMIN", "管理员", List.of(values)));
        }
        PersonalIdentityRespDTO person(String status, String binding) {
            return new PersonalIdentityRespDTO(true, status, "", binding, "identified", "", "", "", "", "", "", "");
        }
    }
}
