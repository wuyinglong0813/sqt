package com.tradepass.framework.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.tradepass.framework.common.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class PdfPagePreviewTest {
    @Test
    void rendersEveryActualPageAndReusesTheExactPdfSnapshot() throws Exception {
        byte[] pdf = pdf(3);
        AtomicInteger loads = new AtomicInteger();
        var source = new PdfPagePreview.Source(PdfPagePreview.fingerprint(pdf), () -> { loads.incrementAndGet(); return pdf; });
        PdfPagePreview previews = new PdfPagePreview();
        var manifest = previews.manifest(source);
        assertThat(manifest.totalPages()).isEqualTo(3);
        byte[] first = Base64.getDecoder().decode(previews.chunk(source, 1, manifest.previewKey(), 0, 655360).contentBase64());
        byte[] last = Base64.getDecoder().decode(previews.chunk(source, 3, manifest.previewKey(), 0, 655360).contentBase64());
        assertThat(ImageIO.read(new ByteArrayInputStream(first))).isNotNull();
        assertThat(first).isNotEqualTo(last);
        assertThat(loads.get()).isOne();
        var tail = previews.chunk(source, 3, manifest.previewKey(), last.length - 7, 655360);
        assertThat(tail.eof()).isTrue();
        assertThat(Base64.getDecoder().decode(tail.contentBase64())).containsExactly(java.util.Arrays.copyOfRange(last, last.length - 7, last.length));
    }

    @Test
    void rejectsChangedVersionsInvalidPageAndUnboundedChunkRequests() throws Exception {
        byte[] pdf = pdf(2);
        var source = new PdfPagePreview.Source(PdfPagePreview.fingerprint(pdf), () -> pdf);
        PdfPagePreview previews = new PdfPagePreview();
        assertThatThrownBy(() -> previews.chunk(source, 1, "old", 0, 10))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        assertThatThrownBy(() -> previews.chunk(source, 0, source.version(), 0, 10)).hasMessageContaining("页码");
        assertThatThrownBy(() -> previews.chunk(source, 3, source.version(), 0, 10)).hasMessageContaining("页码超出");
        assertThatThrownBy(() -> previews.chunk(source, 1, source.version(), -1, 10)).hasMessageContaining("分片参数");
        assertThatThrownBy(() -> previews.chunk(source, 1, source.version(), 0, 655361)).hasMessageContaining("分片参数");
        assertThatThrownBy(() -> previews.chunk(source, 1, source.version(), Long.MAX_VALUE, 10)).hasMessageContaining("位置超出");
    }

    @Test
    void limitsPageCountInputBytesAndRasterDimensions() throws Exception {
        PdfPagePreview previews = new PdfPagePreview();
        byte[] excessivePages = pdf(501);
        assertThatThrownBy(() -> previews.manifest(new PdfPagePreview.Source(PdfPagePreview.fingerprint(excessivePages), () -> excessivePages)))
                .hasMessageContaining("页数超出");
        byte[] excessiveBytes = new byte[PdfPagePreview.MAX_PDF_BYTES + 1];
        assertThatThrownBy(() -> previews.manifest(new PdfPagePreview.Source(PdfPagePreview.fingerprint(excessiveBytes), () -> excessiveBytes)))
                .hasMessageContaining("过大");
        byte[] hugePage;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(20000, 20000)));
            document.save(output);
            hugePage = output.toByteArray();
        }
        var source = new PdfPagePreview.Source(PdfPagePreview.fingerprint(hugePage), () -> hugePage);
        var png = previews.chunk(source, 1, source.version(), 0, 655360);
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(png.contentBase64())));
        assertThat(image.getWidth()).isLessThanOrEqualTo(2400);
        assertThat((long) image.getWidth() * image.getHeight()).isLessThanOrEqualTo(4_000_000);
    }

    @Test
    void refusesMalformedAndEncryptedPdfContent() throws Exception {
        byte[] invalid = "this is not a PDF".getBytes();
        PdfPagePreview previews = new PdfPagePreview();
        assertThatThrownBy(() -> previews.manifest(new PdfPagePreview.Source(PdfPagePreview.fingerprint(invalid), () -> invalid)))
                .hasMessageContaining("预览生成失败");
        byte[] encrypted;
        try (PDDocument document = Loader.loadPDF(pdf(1)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "", new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            document.protect(policy);
            document.save(output);
            encrypted = output.toByteArray();
        }
        assertThatThrownBy(() -> previews.manifest(new PdfPagePreview.Source(PdfPagePreview.fingerprint(encrypted), () -> encrypted)))
                .hasMessageContaining("加密");
    }

    private byte[] pdf(int pages) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, output);
        document.open();
        for (int page = 1; page <= pages; page++) {
            if (page > 1) document.newPage();
            document.add(new Paragraph("Page " + page));
        }
        document.close();
        return output.toByteArray();
    }

}
