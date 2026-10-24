package com.tradepass.module.contract.service.signing;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.contract.TradeService;
import com.tradepass.module.contract.service.membership.MembershipService;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FadadaSigningPrerequisitesTest {
    @AfterEach void clearContext() { AuthContext.clear(); }

    @Test void aNewInitiatorIsPreparedBeforeAnyPdfTaskOrQuotaWork() {
        var f = new Fixture();
        assertThat(f.service.signingPrerequisites(12L)).containsEntry("status", "READY");
        var order = inOrder(f.access, f.companies);
        order.verify(f.access).requirePermission(3L, "contract_sign");
        order.verify(f.companies).requireCurrentSigningAuthorization(3L);
        verify(f.companies, never()).requireCurrentSigningMember(anyLong());
        f.assertNoBillableWork();
    }

    @Test void missingOrganizationConsentKeepsItsRecoveryCodeAndCannotReachQuotaWork() {
        var f = new Fixture();
        doThrow(new BusinessException(46101, "请补充组织数据授权"))
                .when(f.companies).requireCurrentSigningAuthorization(3L);
        assertThatThrownBy(() -> f.service.signingPrerequisites(12L))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(46101));
        verify(f.companies, never()).requireCurrentSigningMember(anyLong());
        f.assertNoBillableWork();
    }

    @Test void anExistingTaskDoesNotCheckTheCompanysReplacementDefaultSeal() {
        var f = new Fixture(); f.existingTask();
        assertThat(f.service.signingPrerequisites(12L)).containsEntry("status", "READY");
        verify(f.companies).requireCurrentSigningMember(3L);
        verify(f.companies, never()).requireCurrentSigningAuthorization(anyLong());
        f.assertNoBillableWork();
    }

    @Test void aCounterpartyDoesNotStartIdentityWorkBeforeTheInitiatorHasSigned() {
        var f = new Fixture(); f.existingTask(); AuthContext.set(8L, 4L);
        assertThatThrownBy(() -> f.service.signingPrerequisites(12L)).hasMessageContaining("等待合同发起方");
        verifyNoInteractions(f.companies);
        f.assertNoBillableWork();
    }

    @Test void aReadyCounterpartyCanContinueWithoutTheCurrentDefaultSeal() {
        var f = new Fixture(); var task = f.existingTask(); task.setInitiatorSignStatus("signed");
        AuthContext.set(8L, 4L);
        assertThat(f.service.signingPrerequisites(12L)).containsEntry("status", "READY");
        verify(f.companies).requireCurrentSigningMember(4L);
        verify(f.companies, never()).requireCurrentSigningAuthorization(anyLong());
        f.assertNoBillableWork();
    }

    @Test void anEndedContractOrTaskCannotBeReportedReady() {
        var f = new Fixture(); f.contract.setStatus("ACTIVE");
        assertThatThrownBy(() -> f.service.signingPrerequisites(12L)).hasMessageContaining("不在待签署");
        f.contract.setStatus("PENDING"); f.existingTask().setProviderStatus("expired");
        assertThatThrownBy(() -> f.service.signingPrerequisites(12L)).hasMessageContaining("签署任务已结束");
        verifyNoInteractions(f.companies); f.assertNoBillableWork();
    }

    @Test void revokedLocalPermissionStopsBeforeReadingTheContractOrProviderIdentity() {
        var f = new Fixture();
        doThrow(new BusinessException("无签署权限")).when(f.access).requirePermission(3L, "contract_sign");
        assertThatThrownBy(() -> f.service.signingPrerequisites(12L)).hasMessage("无签署权限");
        verify(f.contracts, never()).selectById(anyLong());
        verifyNoInteractions(f.companies); f.assertNoBillableWork();
    }

    private static class Fixture {
        final FadadaContractSignTaskMapper tasks = mock(FadadaContractSignTaskMapper.class);
        final TradeContractMapper contracts = mock(TradeContractMapper.class);
        final AccessControlOperations access = mock(AccessControlOperations.class);
        final FadadaCompanyOperations companies = mock(FadadaCompanyOperations.class);
        final FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
        final ContractPdfService pdf = mock(ContractPdfService.class);
        final MembershipService membership = mock(MembershipService.class);
        final TradeContractDO contract = new TradeContractDO();
        final FadadaContractSigningServiceImpl service;
        Fixture() {
            MybatisTestSupport.initialize(TradeContractDO.class, FadadaContractSignTaskDO.class);
            AuthContext.set(7L, 3L);
            var properties = new FadadaProperties();
            properties.setEnabled(true); properties.setAppId("app"); properties.setAppSecret("secret");
            properties.setServerUrl("https://example.test/api"); properties.setCallbackUrl("https://example.test/callback");
            service = new FadadaContractSigningServiceImpl(tasks, contracts, mock(CompanyReader.class), access,
                    companies, gateway, pdf, mock(ContractArchiveService.class), mock(TradeService.class),
                    properties, mock(JdbcTemplate.class));
            service.setMembershipService(membership);
            contract.setId(12L); contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L);
            contract.setStatus("PENDING"); contract.setVersionNo(1);
            when(contracts.selectById(12L)).thenReturn(contract);
            when(contracts.selectByIdForUpdate(12L)).thenReturn(contract);
        }
        FadadaContractSignTaskDO existingTask() {
            var task = new FadadaContractSignTaskDO();
            task.setSignTaskId("task-12"); task.setInitiatorCompanyId(3L); task.setCounterpartyCompanyId(4L);
            task.setProviderStatus("sign_progress");
            when(tasks.selectOne(any())).thenReturn(task);
            return task;
        }
        void assertNoBillableWork() {
            verifyNoInteractions(pdf, gateway);
            verify(membership, never()).reserve(any(), anyLong(), anyInt(), anyString(), anyString());
            verify(membership, never()).confirm(anyLong(), any());
            verify(tasks, never()).insert(any(FadadaContractSignTaskDO.class));
        }
    }
}
