package com.tradepass.module.contract.service.signing;

import com.tradepass.module.contract.service.archive.ContractArchiveService;
import com.tradepass.module.contract.service.archive.ContractPdfService;
import com.tradepass.module.contract.service.contract.TradeService;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations;
import com.tradepass.module.identity.api.fadada.FadadaCompanyOperations.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.framework.common.core.AuthContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        MybatisTestSupport.initialize(FadadaContractSignTaskDO.class, TradeContractDO.class);
        FadadaContractSignTaskMapper taskMapper = mock(FadadaContractSignTaskMapper.class);
        TradeContractMapper contractMapper = mock(TradeContractMapper.class);
        FadadaCompanyOperations companyService = mock(FadadaCompanyOperations.class);
        FadadaSigningGateway gateway = mock(FadadaSigningGateway.class);
        FadadaProperties properties = configuredProperties();
        FadadaContractSigningService service = new FadadaContractSigningServiceImpl(
                taskMapper, contractMapper, mock(CompanyReader.class),
                mock(AccessControlOperations.class), companyService, gateway,
                mock(ContractPdfService.class), mock(ContractArchiveService.class),
                mock(TradeService.class), properties, mock(JdbcTemplate.class));

        TradeContractDO contract = new TradeContractDO();
        contract.setId(12L);
        contract.setCompanyId(3L);
        contract.setCounterpartyCompanyId(4L);
        contract.setVersionNo(1);
        FadadaContractSignTaskDO task = new FadadaContractSignTaskDO();
        task.setContractId(12L);
        task.setVersionNo(1);
        task.setSignTaskId("sign-task-12");
        task.setArchivedAt(LocalDateTime.now());
        FadadaCorpIdentityRespDTO identity = new FadadaCorpIdentityRespDTO();
        identity.setOpenCorpId("corp-3");
        byte[] preview = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        when(contractMapper.selectById(12L)).thenReturn(contract);
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        when(companyService.requireVerified(3L)).thenReturn(identity);
        when(gateway.downloadSignedPreviewPage("sign-task-12", "corp-3")).thenReturn(preview);
        AuthContext.set(7L, 3L);

        assertThat(service.signedPreview(12L).data()).containsExactly(preview);
        assertThat(service.signedPreview(12L).data()).containsExactly(preview);
        verify(gateway, times(1)).downloadSignedPreviewPage("sign-task-12", "corp-3");
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
