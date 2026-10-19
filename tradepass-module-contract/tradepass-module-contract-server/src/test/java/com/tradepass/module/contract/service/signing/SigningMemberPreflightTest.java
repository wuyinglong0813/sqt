package com.tradepass.module.contract.service.signing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.service.abolish.ContractAbolishIntentService;
import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.contract.TradeService;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SigningMemberPreflightTest {
    private final TradeContractMapper contracts = mock(TradeContractMapper.class);
    private final FadadaContractSignTaskMapper tasks = mock(FadadaContractSignTaskMapper.class);
    private final FadadaCompanyOperations companies = mock(FadadaCompanyOperations.class);
    private final FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
    private final ContractAbolishIntentService intents = mock(ContractAbolishIntentService.class);
    private final ContractPdfService pdf = mock(ContractPdfService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TradeContractDO contract = new TradeContractDO();
    private final FadadaContractSignTaskDO task = new FadadaContractSignTaskDO();
    private FadadaContractSigningServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(FadadaContractSignTaskDO.class);
        FadadaProperties properties = new FadadaProperties();
        properties.setEnabled(true); properties.setAppId("app"); properties.setAppSecret("secret");
        properties.setServerUrl("https://api.test"); properties.setCallbackUrl("https://example.test/callback");
        service = new FadadaContractSigningServiceImpl(tasks, contracts, mock(CompanyReader.class),
                mock(AccessControlOperations.class), companies, gateway, pdf,
                mock(ContractArchiveService.class), mock(TradeService.class), properties, jdbc);
        service.setAbolishIntentService(intents);
        contract.setId(12L); contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L);
        contract.setStatus("PENDING"); contract.setVersionNo(1);
        task.setId(20L); task.setContractId(12L); task.setVersionNo(1); task.setSignTaskId("original");
        task.setInitiatorCompanyId(3L); task.setCounterpartyCompanyId(4L);
        task.setInitiatorActorId("supplier"); task.setCounterpartyActorId("buyer");
        when(contracts.selectByIdForUpdate(12L)).thenReturn(contract);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        AuthContext.set(7L, 3L);
    }

    @AfterEach
    void clearContext() { AuthContext.clear(); }

    @Test
    void missingProviderMembershipBlocksPreparationBeforePdfOrTaskCreation() {
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(null);
        doThrow(new BusinessException("请先完成电子签企业成员激活"))
                .when(companies).requireCurrentSigningMember(3L);

        assertThatThrownBy(() -> service.signUrl(12L)).hasMessageContaining("成员激活");

        verifyNoInteractions(gateway, pdf);
        verify(tasks, never()).insert(any(FadadaContractSignTaskDO.class));
    }

    @Test
    void recipientMembershipIsCheckedForItsOwnEnterpriseBeforeIssuingALink() {
        AuthContext.set(8L, 4L);
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "sign_progress",
                List.of(new FadadaSigningGateway.ActorStatus("supplier", "signed"))));
        when(gateway.actorUrl(anyString(), anyString(), anyString(), anyString())).thenReturn("https://example.test/sign");

        service.signUrl(12L);

        var order = inOrder(companies, gateway);
        order.verify(companies).requireCurrentSigningMember(4L);
        order.verify(gateway).status("original");
        order.verify(gateway).actorUrl(eq("original"), eq("buyer"), eq("tradepass-user-8"), anyString());
        verify(companies, never()).requireCurrentSigningAuthorization(anyLong());
    }

    @Test
    void aNewTaskChecksTheConfiguredSealBeforePreparingABillableProviderTask() {
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(null);
        doThrow(new BusinessException("请企业印章管理员先授权签署"))
                .when(companies).requireCurrentSigningAuthorization(3L);

        assertThatThrownBy(() -> service.signUrl(12L)).hasMessageContaining("先授权签署");

        verify(companies).requireCurrentSigningMember(3L);
        verifyNoInteractions(gateway, pdf);
        verify(tasks, never()).insert(any(FadadaContractSignTaskDO.class));
    }

    @Test
    void missingMembershipDoesNotCreateAnAbolishIntentOrRemoteAgreement() {
        contract.setStatus("ACTIVE");
        when(jdbc.queryForList(contains("action_type = 'VOID'"), eq(Long.class), eq(12L))).thenReturn(List.of(1L));
        doThrow(new BusinessException("请先完成电子签企业成员激活"))
                .when(companies).requireCurrentSigningMember(3L);

        assertThatThrownBy(() -> service.abolishUrl(12L, "双方协商作废")).hasMessageContaining("成员激活");

        verifyNoInteractions(gateway, intents);
    }
}
