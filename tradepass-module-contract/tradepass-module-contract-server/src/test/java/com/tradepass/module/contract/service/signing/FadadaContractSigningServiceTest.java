package com.tradepass.module.contract.service.signing;

import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.contract.TradeService;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.contract.dal.dataobject.signing.FadadaContractSignTaskDO;
import com.tradepass.module.identity.api.fadada.dto.FadadaCorpIdentityRespDTO;
import com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO;
import com.tradepass.framework.fadada.core.FadadaSigningGateway;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import com.tradepass.module.contract.dal.mysql.signing.FadadaContractSignTaskMapper;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FadadaContractSigningServiceTest {

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void cachesImmutableSignedPreviewInsteadOfDownloadingProviderZipForEveryChunk() {
        var f = new Fixture();

        var manifest = f.service.signedPreviewManifest(12L);
        assertThat(manifest.pageCount()).isEqualTo(10);
        assertThat(manifest.archiveKey()).isEqualTo("sign-task-12");
        assertThat(f.service.signedPreviewPage(12L, 1, "sign-task-12").data()).containsExactly(f.pages.get(0));
        assertThat(f.service.signedPreviewPage(12L, 2, "sign-task-12").data()).containsExactly(f.pages.get(1));
        assertThat(f.service.signedPreviewPage(12L, 10, "sign-task-12").data()).containsExactly(f.pages.get(9));
        assertThat(f.service.signedPreviewPage(12L, 1, "sign-task-12").fileName()).isEqualTo("合同第1页.png");
        assertThat(f.service.signedPreview(12L).data()).containsExactly(f.pages.get(9));
        assertThat(f.service.signedPreviewManifest(12L)).isEqualTo(manifest);
        verify(f.gateway, times(1)).downloadSignedPreviewPages("sign-task-12", "corp-3");
        verify(f.gateway, never()).downloadSignedPreviewPage(any(), any());
    }

    @Test
    void rejectsPageNumbersOutsideTheArchivedDocument() {
        var f = new Fixture();
        assertThatThrownBy(() -> f.service.signedPreviewPage(12L, 0, "sign-task-12"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("页码不正确");
        assertThatThrownBy(() -> f.service.signedPreviewPage(12L, -1, "sign-task-12"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("页码不正确");
        verify(f.gateway, never()).downloadSignedPreviewPages(any(), any());
        assertThatThrownBy(() -> f.service.signedPreviewPage(12L, 11, "sign-task-12"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("页码超出范围");
    }

    @Test
    void checksPermissionAndContractPartyEvenWhenTheArchiveIsCached() {
        var f = new Fixture();
        f.service.signedPreviewManifest(12L);
        AuthContext.set(8L, 4L);
        assertThat(f.service.signedPreviewPage(12L, 1, "sign-task-12").data()).containsExactly(f.pages.get(0));
        doThrow(new BusinessException("无合同权限")).when(f.accessControl)
                .requireAnyPermission(4L, "contract_view", "contract_sign");
        assertThatThrownBy(() -> f.service.signedPreviewManifest(12L)).hasMessage("无合同权限");
        assertThatThrownBy(() -> f.service.signedPreviewPage(12L, 1, "sign-task-12")).hasMessage("无合同权限");

        AuthContext.set(9L, 5L);
        assertThatThrownBy(() -> f.service.signedPreviewManifest(12L)).hasMessage("合同不存在");
        assertThatThrownBy(() -> f.service.signedPreviewPage(12L, 1, "sign-task-12")).hasMessage("合同不存在");
        verify(f.gateway, times(1)).downloadSignedPreviewPages(any(), any());
    }

    @Test
    void doesNotExposeUnarchivedPagesOrAnOlderCachedArchive() {
        var f = new Fixture();
        f.service.signedPreviewManifest(12L);
        f.task.setArchivedAt(null);
        assertThatThrownBy(() -> f.service.signedPreviewManifest(12L)).hasMessageContaining("尚未归档");
        assertThatThrownBy(() -> f.service.signedPreviewPage(12L, 1, "sign-task-12")).hasMessageContaining("尚未归档");
        verify(f.gateway, times(1)).downloadSignedPreviewPages(any(), any());
    }

    @Test
    void isolatesAReplacementArchivedTaskFromThePreviousCachedPages() {
        var f = new Fixture();
        var original = f.service.signedPreviewManifest(12L);
        f.contract.setVersionNo(2);
        f.task.setVersionNo(2);
        f.task.setSignTaskId("sign-task-12-v2");
        byte[] replacementPage = new byte[]{1, 2, 3};
        when(f.gateway.downloadSignedPreviewPages("sign-task-12-v2", "corp-3"))
                .thenReturn(List.of(replacementPage));

        var replacement = f.service.signedPreviewManifest(12L);
        assertThat(replacement.pageCount()).isEqualTo(1);
        assertThat(replacement.archiveKey()).isNotEqualTo(original.archiveKey());
        assertThatThrownBy(() -> f.service.signedPreviewPage(12L, 1, original.archiveKey()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("归档");
        assertThat(f.service.signedPreviewPage(12L, 1, replacement.archiveKey()).data()).containsExactly(replacementPage);
        assertThatThrownBy(() -> f.service.signedPreviewPage(12L, 2, replacement.archiveKey())).hasMessageContaining("页码超出范围");
        verify(f.gateway, times(1)).downloadSignedPreviewPages("sign-task-12", "corp-3");
        verify(f.gateway, times(1)).downloadSignedPreviewPages("sign-task-12-v2", "corp-3");
    }

    private class Fixture {
        final FadadaContractSignTaskMapper taskMapper = mock(FadadaContractSignTaskMapper.class);
        final TradeContractMapper contractMapper = mock(TradeContractMapper.class);
        final FadadaCompanyOperations companyService = mock(FadadaCompanyOperations.class);
        final AccessControlOperations accessControl = mock(AccessControlOperations.class);
        final FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
        final TradeContractDO contract = new TradeContractDO();
        final FadadaContractSignTaskDO task = new FadadaContractSignTaskDO();
        final List<byte[]> pages = IntStream.rangeClosed(1, 10)
                .mapToObj(page -> new byte[]{(byte) 0x89, 'P', 'N', 'G', (byte) page}).toList();
        final FadadaContractSigningService service = new FadadaContractSigningServiceImpl(
                taskMapper, contractMapper, mock(CompanyReader.class), accessControl, companyService, gateway,
                mock(ContractPdfService.class), mock(ContractArchiveService.class), mock(TradeService.class),
                configuredProperties(), mock(JdbcTemplate.class));

        Fixture() {
            MybatisTestSupport.initialize(FadadaContractSignTaskDO.class, TradeContractDO.class);
            contract.setId(12L);
            contract.setCompanyId(3L);
            contract.setCounterpartyCompanyId(4L);
            contract.setVersionNo(1);
            task.setContractId(12L);
            task.setVersionNo(1);
            task.setSignTaskId("sign-task-12");
            task.setArchivedAt(LocalDateTime.now());
            FadadaCorpIdentityRespDTO identity = new FadadaCorpIdentityRespDTO();
            identity.setOpenCorpId("corp-3");
            when(contractMapper.selectById(12L)).thenReturn(contract);
            when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
            when(companyService.requireVerified(3L)).thenReturn(identity);
            when(gateway.downloadSignedPreviewPages("sign-task-12", "corp-3")).thenReturn(pages);
            AuthContext.set(7L, 3L);
        }
    }

    private FadadaProperties configuredProperties() {
        FadadaProperties properties = new FadadaProperties();
        properties.setEnabled(true);
        properties.setAppId("app-id");
        properties.setAppSecret("app-secret");
        properties.setServerUrl("https://api.fadada.com/api/v5");
        properties.setCallbackUrl("https://example.test/callback");
        return properties;
    }
}
