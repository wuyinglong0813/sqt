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
