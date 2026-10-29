package com.tradepass.module.trade.controller.app.document;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.pdf.PdfPagePreview;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import com.tradepass.module.trade.service.document.BusinessDocumentPdfService;
import com.tradepass.module.trade.service.document.BusinessDocumentService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessDocumentPreviewControllerTest {
    @Test
    void previewsAllPagesButRechecksDocumentAccessEvenOnCacheHits() throws Exception {
        BusinessDocumentService documents = mock(BusinessDocumentService.class);
        BusinessDocumentPdfService pdfs = mock(BusinessDocumentPdfService.class);
        BusinessDocumentDO document = new BusinessDocumentDO();
        byte[] pdf = pdf();
        var source = new PdfPagePreview.Source(PdfPagePreview.fingerprint(pdf), () -> pdf);
        when(documents.getDocument(31L)).thenReturn(document);
        when(pdfs.previewSource(document)).thenReturn(source);
        var controller = new BusinessDocumentController(documents, pdfs);
        var manifest = controller.previewManifest(31L).data();
        assertThat(manifest.totalPages()).isEqualTo(2);
        assertThat(controller.previewChunkData(31L, 2, manifest.previewKey(), 0, 655360).data().contentType()).isEqualTo("image/png");
        when(documents.getDocument(31L)).thenThrow(new BusinessException("无单据查看权限"));
        assertThatThrownBy(() -> controller.previewManifest(31L)).hasMessage("无单据查看权限");
        assertThatThrownBy(() -> controller.previewChunkData(31L, 2, manifest.previewKey(), 0, 655360)).hasMessage("无单据查看权限");
        verify(documents, times(4)).getDocument(31L);
    }

    @Test
    void oldKeysCannotReadARevisedDocument() throws Exception {
        BusinessDocumentService documents = mock(BusinessDocumentService.class);
        BusinessDocumentPdfService pdfs = mock(BusinessDocumentPdfService.class);
        BusinessDocumentDO document = new BusinessDocumentDO();
        byte[] pdf = pdf();
        when(documents.getDocument(31L)).thenReturn(document);
        when(pdfs.previewSource(document)).thenReturn(new PdfPagePreview.Source(PdfPagePreview.fingerprint("first".getBytes()), () -> pdf));
        var controller = new BusinessDocumentController(documents, pdfs);
        String original = controller.previewManifest(31L).data().previewKey();
        when(pdfs.previewSource(document)).thenReturn(new PdfPagePreview.Source(PdfPagePreview.fingerprint("revised".getBytes()), () -> pdf));
        assertThatThrownBy(() -> controller.previewChunkData(31L, 1, original, 0, 10))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
    }

    private byte[] pdf() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document pdf = new Document();
        PdfWriter.getInstance(pdf, output);
        pdf.open(); pdf.add(new Paragraph("First page")); pdf.newPage(); pdf.add(new Paragraph("Second page")); pdf.close();
        return output.toByteArray();
    }
}
