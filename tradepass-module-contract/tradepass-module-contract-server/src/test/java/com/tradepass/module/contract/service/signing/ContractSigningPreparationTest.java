package com.tradepass.module.contract.service.signing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.framework.fadada.core.FadadaTaskPreparationException;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.contract.TradeService;
import com.tradepass.module.contract.service.membership.MembershipService;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.fadada.dto.FadadaCorpIdentityRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContractSigningPreparationTest {
    private final TradeContractMapper contracts = mock(TradeContractMapper.class);
    private final FadadaContractSignTaskMapper tasks = mock(FadadaContractSignTaskMapper.class);
    private final CompanyReader companies = mock(CompanyReader.class);
    private final FadadaCompanyOperations identities = mock(FadadaCompanyOperations.class);
    private final FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
    private final ContractPdfService pdf = mock(ContractPdfService.class);
    private final TradeService trade = mock(TradeService.class);
    private final MembershipService membership = mock(MembershipService.class);
    private final TradeContractDO contract = new TradeContractDO();
    private final ContractRespDTO snapshot = new ContractRespDTO("12", "HT-12", "3", "4", "对方", "SALE",
            "合同", "模板", BigDecimal.TEN, "2026-10-19", null, "{}", "PENDING", 1, "7", null, null,
            "2026-10-19", "本方", "对方", "3", "4", "对方", "SALE", "OUTGOING");
    private FadadaContractSigningServiceImpl service;

    @BeforeEach void setUp() {
        MybatisTestSupport.initialize(FadadaContractSignTaskDO.class);
        var properties = new FadadaProperties(); properties.setEnabled(true); properties.setAppId("app");
        properties.setAppSecret("secret"); properties.setServerUrl("https://example.test");
        properties.setCallbackUrl("https://example.test/callback");
        service = new FadadaContractSigningServiceImpl(tasks, contracts, companies, mock(AccessControlOperations.class),
                identities, gateway, pdf, mock(ContractArchiveService.class), trade, properties, mock(JdbcTemplate.class));
        service.setMembershipService(membership);
        contract.setId(12L); contract.setCompanyId(3L); contract.setCounterpartyCompanyId(4L);
        contract.setVersionNo(1); contract.setDirection("SALE"); contract.setName("合同"); contract.setStatus("PENDING");
        when(contracts.selectByIdForUpdate(12L)).thenReturn(contract);
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(null);
        for (long id : List.of(3L, 4L)) {
            var company = new CompanyRespDTO(); company.setId(id); company.setName("公司" + id);
            when(companies.selectById(id)).thenReturn(company);
            var identity = new FadadaCorpIdentityRespDTO(); identity.setOpenCorpId("corp-" + id);
            when(identities.requireVerified(id)).thenReturn(identity);
            when(identities.enabledSealId(id)).thenReturn(String.valueOf(id));
        }
        when(trade.contractForElectronicSignature(12L, 3L)).thenReturn(snapshot);
        when(pdf.generateForSigning(any())).thenReturn(new ContractPdfService.SigningDocument(new byte[]{1},
                new FadadaSigningGateway.SealPosition(2, "220", "600"), new FadadaSigningGateway.SealPosition(2, "580", "600")));
        when(pdf.fileName(any())).thenReturn("合同.pdf");
        when(membership.reserve(any(), eq(12L), eq(1), anyString(), anyString()))
                .thenReturn(new MembershipService.Reservation(9L, "RESERVED", true, null, null, null, null, null));
        AuthContext.set(7L, 3L);
    }

    @AfterEach void clear() { AuthContext.clear(); }

    @ParameterizedTest @ValueSource(strings = {"SALE", "PURCHASE"})
    void newSigningAlwaysChargesTheContractInitiatorRegardlessOfTradeDirection(String direction) {
        contract.setDirection(direction);
        when(gateway.createTask(any())).thenReturn(new FadadaSigningGateway.CreatedTask("new-task", "file", "doc"));
        when(gateway.status("new-task")).thenReturn(new FadadaSigningGateway.TaskStatus("new-task", "sign_progress", List.of()));
        when(gateway.actorUrl(anyString(), anyString(), anyString(), anyString())).thenReturn("https://example.test/sign");
        service.signUrl(12L);
        verify(membership).reserve(argThat(company -> company.getId().equals(3L)), eq(12L), eq(1), anyString(), anyString());
        verify(gateway).createTask(argThat(command -> command.initiatorOpenCorpId().equals("corp-3")
                && command.counterpartyOpenCorpId().equals("corp-4")));
        verify(membership).confirm(eq(9L), any());
    }

    @Test void recipientSignsTheExistingTaskWithoutReservingOrChargingQuota() {
        var existing = new FadadaContractSignTaskDO(); existing.setId(20L); existing.setContractId(12L);
        existing.setVersionNo(1); existing.setSignTaskId("original"); existing.setInitiatorCompanyId(3L);
        existing.setCounterpartyCompanyId(4L); existing.setInitiatorActorId("supplier"); existing.setCounterpartyActorId("buyer");
        existing.setInitiatorSignStatus("signed");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "sign_progress",
                List.of(new FadadaSigningGateway.ActorStatus("supplier", "signed"))));
        when(gateway.actorUrl(anyString(), anyString(), anyString(), anyString())).thenReturn("https://example.test/sign");
        AuthContext.set(8L, 4L);
        service.signUrl(12L);
        verifyNoInteractions(pdf, membership);
        verify(gateway, never()).createTask(any());
        verify(gateway).actorUrl(eq("original"), eq("buyer"), anyString(), anyString());
    }

    @Test void confirmedFilePreparationFailureReleasesQuotaAndAllowsAnotherAttempt() {
        when(gateway.createTask(any())).thenThrow(new FadadaTaskPreparationException("文件处理失败，请重试"));
        assertThatThrownBy(() -> service.signUrl(12L)).hasMessageContaining("文件处理失败");
        verify(membership).releaseBeforeTaskCreation(9L);
        verify(membership, never()).uncertain(anyLong());
        assertThatThrownBy(() -> service.signUrl(12L)).hasMessageContaining("文件处理失败");
        verify(gateway, times(2)).createTask(any());
    }

    @Test void lostCreationResponseKeepsTheReservationUncertain() {
        when(gateway.createTask(any())).thenThrow(new BusinessException("create timeout"));
        assertThatThrownBy(() -> service.signUrl(12L)).hasMessageContaining("create timeout");
        verify(membership).uncertain(9L);
        verify(membership, never()).releaseBeforeTaskCreation(anyLong());
    }

    @Test void existingProviderTaskNeverRegeneratesItsDocumentOrChangesItsCoordinates() {
        var existing = new FadadaContractSignTaskDO(); existing.setId(20L); existing.setContractId(12L);
        existing.setVersionNo(1); existing.setSignTaskId("original"); existing.setInitiatorCompanyId(3L);
        existing.setCounterpartyCompanyId(4L); existing.setInitiatorActorId("supplier"); existing.setCounterpartyActorId("buyer");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "sign_progress", List.of()));
        when(gateway.actorUrl(anyString(), anyString(), anyString(), anyString())).thenReturn("https://example.test/sign");
        service.signUrl(12L);
        verifyNoInteractions(pdf, membership);
        verify(gateway, never()).createTask(any());
    }

    @Test void consumedReceiptRestoresTheFrozenProviderTaskWithoutGeneratingAnotherDocument() throws Exception {
        var json = new ObjectMapper().writeValueAsString(snapshot);
        when(membership.existing(12L, 1)).thenReturn(new MembershipService.Reservation(9L, "CONSUMED", false,
                "original", "original-file", "original-doc", json, "original-hash"));
        when(gateway.status("original")).thenReturn(new FadadaSigningGateway.TaskStatus("original", "sign_progress", List.of()));
        when(gateway.actorUrl(anyString(), anyString(), anyString(), anyString())).thenReturn("https://example.test/sign");
        service.signUrl(12L);
        verifyNoInteractions(pdf);
        verify(gateway, never()).createTask(any());
        verify(membership, never()).reserve(any(), anyLong(), anyInt(), anyString(), anyString());
        verify(tasks).insert(argThat((FadadaContractSignTaskDO restored) -> "original".equals(restored.getSignTaskId())
                && "original-file".equals(restored.getSourceFileId()) && "original-hash".equals(restored.getSourceSha256())
                && json.equals(restored.getContractSnapshot())));
    }
}
