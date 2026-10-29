package com.tradepass.module.settlement.controller.app.collaboration;

import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations;
import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations.*;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.FileChunkDataPayload;
import com.tradepass.module.settlement.service.attachment.ContractAttachmentService;
import com.tradepass.module.settlement.service.reconciliation.ReconciliationAccountService;
import com.tradepass.module.settlement.service.reconciliation.ReconciliationStatementService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollaborationControllerTest {

    @Test
    void rendersPdfAttachmentPagesAndNeverUsesCacheToBypassAttachmentPermissions() throws Exception {
        ContractAttachmentService attachments = mock(ContractAttachmentService.class);
        CollaborationController controller = new CollaborationController(attachments,
                mock(ReconciliationStatementService.class), mock(ReconciliationAccountService.class));
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        com.lowagie.text.Document pdf = new com.lowagie.text.Document();
        com.lowagie.text.pdf.PdfWriter.getInstance(pdf, output);
        pdf.open(); pdf.add(new com.lowagie.text.Paragraph("Invoice page one")); pdf.newPage();
        pdf.add(new com.lowagie.text.Paragraph("Invoice page two")); pdf.close();
        when(attachments.getFile(19L)).thenReturn(new ContractAttachmentOperations.FileRespDTO(
                19L, 12L, "发票.pdf", "application/pdf", output.toByteArray()));
        var manifest = controller.attachmentPreviewManifest(19L).data();
        assertThat(manifest.totalPages()).isEqualTo(2);
        var image = controller.attachmentPreviewChunkData(19L, 2, manifest.previewKey(), 0, 655360).data();
        assertThat(image.contentType()).isEqualTo("image/png");
        var pageImage = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(image.contentBase64())));
        assertThat(pageImage).isNotNull();
        when(attachments.getFile(19L)).thenThrow(new BusinessException("附件不存在"));
        assertThatThrownBy(() -> controller.attachmentPreviewManifest(19L)).hasMessage("附件不存在");
        assertThatThrownBy(() -> controller.attachmentPreviewChunkData(19L, 2, manifest.previewKey(), 0, 655360)).hasMessage("附件不存在");
    }

    @Test
    void refusesNonPdfAttachmentsAndOldFileVersions() throws Exception {
        ContractAttachmentService attachments = mock(ContractAttachmentService.class);
        CollaborationController controller = new CollaborationController(attachments,
                mock(ReconciliationStatementService.class), mock(ReconciliationAccountService.class));
        when(attachments.getFile(19L)).thenReturn(new ContractAttachmentOperations.FileRespDTO(
                19L, 12L, "照片.png", "image/png", new byte[] { 1, 2, 3 }));
        assertThatThrownBy(() -> controller.attachmentPreviewManifest(19L)).hasMessageContaining("仅 PDF");
        when(attachments.getFile(19L)).thenReturn(new ContractAttachmentOperations.FileRespDTO(
                19L, 12L, "资料.pdf", "application/pdf", new byte[] { 1, 2, 3 }));
        assertThatThrownBy(() -> controller.attachmentPreviewChunkData(19L, 1, "old-version", 0, 10))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
    }

    @Test
    void returnsAuthenticatedAttachmentContentInBoundedChunks() {
        ContractAttachmentService attachmentService = mock(ContractAttachmentService.class);
        CollaborationController controller = new CollaborationController(
                attachmentService,
                mock(ReconciliationStatementService.class),
                mock(ReconciliationAccountService.class));
        byte[] content = "abcdefgh".getBytes(StandardCharsets.UTF_8);
        when(attachmentService.getFile(19L)).thenReturn(
                new ContractAttachmentOperations.FileRespDTO(
                        19L, 12L, "资料.pdf", "application/pdf", content));

        FileChunkDataPayload first = controller.attachmentContentChunkData(19L, 0, 3).data();
        FileChunkDataPayload last = controller.attachmentContentChunkData(19L, 6, 3).data();

        assertThat(new String(Base64.getDecoder().decode(first.contentBase64()), StandardCharsets.UTF_8))
                .isEqualTo("abc");
        assertThat(first.offset()).isZero();
        assertThat(first.length()).isEqualTo(3);
        assertThat(first.totalSize()).isEqualTo(8);
        assertThat(first.eof()).isFalse();
        assertThat(new String(Base64.getDecoder().decode(last.contentBase64()), StandardCharsets.UTF_8))
                .isEqualTo("gh");
        assertThat(last.eof()).isTrue();
        assertThatThrownBy(() -> controller.attachmentContentChunkData(19L, 8, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件分片位置超出范围");
        assertThatThrownBy(() -> controller.attachmentContentChunkData(19L, 0, 3 * 1024 * 1024))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件分片参数不正确");
    }
}
