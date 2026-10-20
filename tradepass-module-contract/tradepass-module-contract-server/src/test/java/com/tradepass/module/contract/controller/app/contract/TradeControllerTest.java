package com.tradepass.module.contract.controller.app.contract;

import com.tradepass.framework.common.pojo.FileChunkDataPayload;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.api.signing.FadadaContractSigningOperations;
import com.tradepass.module.contract.service.signing.FadadaContractSigningService;
import com.tradepass.module.contract.service.contract.TradeService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TradeControllerTest {

    @Test
    void returnsSignedArchivePreviewInBoundedChunks() {
        FadadaContractSigningService signingService = mock(FadadaContractSigningService.class);
        TradeController controller = new TradeController(mock(TradeService.class));
        controller.setSigningService(signingService);
        when(signingService.signedPreview(12L)).thenReturn(
                new FadadaContractSigningOperations.SignedPreview(
                        "合同签章页.png", "preview".getBytes(StandardCharsets.UTF_8)));

        FileChunkDataPayload chunk = controller.signedContractPreviewChunk(12L, 3, 3).data();

        assertThat(new String(Base64.getDecoder().decode(chunk.contentBase64()), StandardCharsets.UTF_8))
                .isEqualTo("vie");
        assertThat(chunk.offset()).isEqualTo(3);
        assertThat(chunk.length()).isEqualTo(3);
        assertThat(chunk.totalSize()).isEqualTo(7);
        assertThat(chunk.eof()).isFalse();
        verify(signingService).signedPreview(12L);
        verify(signingService, never()).signedPreviewPage(any(), anyInt(), any());
    }

    @Test
    void exposesTheCompletePreviewManifest() {
        FadadaContractSigningService signingService = mock(FadadaContractSigningService.class);
        TradeController controller = new TradeController(mock(TradeService.class));
        controller.setSigningService(signingService);
        var manifest = new FadadaContractSigningOperations.SignedPreviewManifest(10, "archive-12");
        when(signingService.signedPreviewManifest(12L)).thenReturn(manifest);

        assertThat(controller.signedContractPreviewManifest(12L).data()).isEqualTo(manifest);
    }

    @Test
    void returnsChunksOfTheRequestedPageAndArchiveInsteadOfTheLegacyLastPage() {
        FadadaContractSigningService signingService = mock(FadadaContractSigningService.class);
        TradeController controller = new TradeController(mock(TradeService.class));
        controller.setSigningService(signingService);
        when(signingService.signedPreviewPage(12L, 2, "archive-12")).thenReturn(
                new FadadaContractSigningOperations.SignedPreview("合同第2页.png", "second-page".getBytes(StandardCharsets.UTF_8)));

        FileChunkDataPayload chunk = controller.signedContractPreviewChunk(12L, 2, "archive-12", 7, 10).data();

        assertThat(new String(Base64.getDecoder().decode(chunk.contentBase64()), StandardCharsets.UTF_8))
                .isEqualTo("page");
        assertThat(chunk.offset()).isEqualTo(7);
        assertThat(chunk.length()).isEqualTo(4);
        assertThat(chunk.totalSize()).isEqualTo(11);
        assertThat(chunk.eof()).isTrue();
        verify(signingService, never()).signedPreview(any());
        verify(signingService).signedPreviewPage(12L, 2, "archive-12");
    }

    @Test
    void rejectsInvalidPageAndChunkArgumentsBeforeReadingAnArchive() {
        FadadaContractSigningService signingService = mock(FadadaContractSigningService.class);
        TradeController controller = new TradeController(mock(TradeService.class));
        controller.setSigningService(signingService);

        assertThatThrownBy(() -> controller.signedContractPreviewChunk(12L, 0, "archive-12", 0, 100))
                .isInstanceOf(BusinessException.class).hasMessageContaining("页码");
        assertThatThrownBy(() -> controller.signedContractPreviewChunk(12L, 1, "archive-12", -1, 100))
                .isInstanceOf(BusinessException.class).hasMessageContaining("分片参数");
        assertThatThrownBy(() -> controller.signedContractPreviewChunk(12L, 1, "archive-12", 0, 0))
                .isInstanceOf(BusinessException.class).hasMessageContaining("分片参数");
        assertThatThrownBy(() -> controller.signedContractPreviewChunk(12L, 1, "archive-12", 0, 640 * 1024 + 1))
                .isInstanceOf(BusinessException.class).hasMessageContaining("分片参数");
        verifyNoInteractions(signingService);
    }
}
