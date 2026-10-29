package com.tradepass.module.trade.service.document;

import com.tradepass.module.trade.service.inventory.SalesOrderSignatureService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import com.tradepass.framework.pdf.PdfPagePreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessDocumentPdfServiceTest {

    @Test
    void previewVersionsFollowRealContentAndSignatureSnapshotsInsteadOfPdfCreationTime() throws Exception {
        SalesOrderSignatureService signatures = mock(SalesOrderSignatureService.class);
        BusinessDocumentPdfService service = new BusinessDocumentPdfServiceImpl(new ObjectMapper(), signatures);
        BusinessDocumentDO source = example("SALES_ORDER", 3);
        var before = service.previewSource(source);
        assertThat(service.previewSource(source).version()).isEqualTo(before.version());
        source.setContent(source.getContent().replace("李制单", "刘制单"));
        var revised = service.previewSource(source);
        assertThat(revised.version()).isNotEqualTo(before.version());
        PdfReader original = new PdfReader(before.load().get());
        assertThat(new PdfTextExtractor(original).getTextFromPage(1)).contains("李制单");
        original.close();
        when(signatures.find(31L)).thenReturn(new SalesOrderSignatureService.Confirmation("张采购",
                LocalDateTime.of(2026, 10, 29, 10, 30), "签名.png", "image/png", png()));
        var signed = service.previewSource(source);
        assertThat(signed.version()).isNotEqualTo(revised.version());
        assertThat(service.previewSource(source).version()).isEqualTo(signed.version());
    }

    @Test
    void realSalesAndReturnPdfPagesPreserveChineseModelsFeesAndConfirmation() throws Exception {
        SalesOrderSignatureService signatures = mock(SalesOrderSignatureService.class);
        when(signatures.find(31L)).thenReturn(new SalesOrderSignatureService.Confirmation("张采购",
                LocalDateTime.of(2026, 10, 29, 10, 30), "签名.png", "image/png", png()));
        BusinessDocumentPdfService service = new BusinessDocumentPdfServiceImpl(new ObjectMapper(), signatures);
        for (String label : java.util.List.of("sales", "return", "sales-multipage")) {
            var document = example(label.equals("return") ? "RETURN_ORDER" : "SALES_ORDER", label.endsWith("multipage") ? 42 : 3);
            var source = service.previewSource(document);
            byte[] pdf = source.load().get();
            PdfReader reader = new PdfReader(pdf);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) text.append(new PdfTextExtractor(reader).getTextFromPage(page));
            assertThat(text).contains("RVVP", "2x1.5", "运输费", "制单人：李制单", "审核人：王审核", "张采购");
            if (label.endsWith("multipage")) assertThat(reader.getNumberOfPages()).isGreaterThan(1);
            PdfPagePreview previews = new PdfPagePreview();
            var manifest = previews.manifest(source);
            assertThat(manifest.totalPages()).isEqualTo(reader.getNumberOfPages());
            reader.close();
            String output = System.getProperty("sqt.pdf.validation.output");
            if (output != null) {
                var directory = java.nio.file.Path.of(output);
                java.nio.file.Files.createDirectories(directory);
                java.nio.file.Files.write(directory.resolve(label + ".pdf"), pdf);
                for (int page = 1; page <= manifest.totalPages(); page++) {
                    ByteArrayOutputStream image = new ByteArrayOutputStream();
                    long offset = 0;
                    while (true) {
                        var chunk = previews.chunk(source, page, manifest.previewKey(), offset, 655360);
                        image.write(java.util.Base64.getDecoder().decode(chunk.contentBase64()));
                        if (chunk.eof()) break;
                        offset += chunk.length();
                    }
                    java.nio.file.Files.write(directory.resolve(label + "-page-" + page + ".png"), image.toByteArray());
                }
            }
        }
    }

    private BusinessDocumentDO example(String type, int rows) throws Exception {
        BusinessDocumentDO document = new BusinessDocumentDO();
        document.setId(31L); document.setDocumentNo(type.equals("RETURN_ORDER") ? "TH-20261029-001" : "XS-20261029-001");
        document.setDocumentType(type); document.setStatus("ACKNOWLEDGED"); document.setSourceType("CONTRACT");
        var content = new ObjectMapper().createObjectNode();
        content.put("title", type.equals("RETURN_ORDER") ? "退货单" : "销售单");
        content.put("companyName", "河北光屿行贸易有限公司"); content.put("counterpartyName", "河北云域科技有限公司");
        content.put("contractNo", "HT-20261029-4BB8A16C"); content.put("date", "2026-10-29");
        long total = (rows / 2) * 5000L + ((rows - 1) / 2) * 40000L + 100;
        content.put("totalAmount", total + ".00"); content.put("preparedByName", "李制单"); content.put("reviewedByName", "王审核"); content.put("blankRows", rows);
        content.set("columns", new ObjectMapper().valueToTree(java.util.List.of("品名", "型号", "规格", "单位", "数量", "单价", "金额", "备注")));
        var goods = content.putArray("rows");
        for (int index = 0; index < rows - 1; index++) {
            goods.add(new ObjectMapper().valueToTree(index % 2 == 0
                    ? java.util.List.of("电线", "RVVP", "2x1.5", "米", "1000", "5", "5000", "样例商品")
                    : java.util.List.of("阀门", "不锈钢球阀", "DN100", "台", "200", "200", "40000", "样例商品")));
        }
        goods.add(new ObjectMapper().valueToTree(java.util.List.of("运输费", "", "", "项", "1", "100", "100", "不计库存")));
        document.setContent(content.toString());
        return document;
    }

    @Test
    void fillsPreparedByAndCustomerConfirmationIntoPdf() throws Exception {
        SalesOrderSignatureService signatureService = mock(SalesOrderSignatureService.class);
        when(signatureService.find(31L)).thenReturn(new SalesOrderSignatureService.Confirmation(
                "张采购", LocalDateTime.of(2026, 8, 16, 14, 30),
                "签名.png", "image/png", png()));
        BusinessDocumentPdfService service = new BusinessDocumentPdfServiceImpl(
                new ObjectMapper(), signatureService);
        BusinessDocumentDO document = new BusinessDocumentDO();
        document.setId(31L);
        document.setDocumentNo("XS-31");
        document.setDocumentType("SALES_ORDER");
        document.setStatus("ACKNOWLEDGED");
        document.setContent("""
                {"title":"销售单","companyName":"供方企业","counterpartyName":"客户企业",
                 "contractNo":"HT-12","date":"2026-08-16","totalAmount":"7.00",
                 "preparedByName":"李制单","reviewedByName":"王审核","blankRows":1,
                 "columns":["品名","数量","单价","金额"],
                 "rows":[["商品A","2","3.5","7"]]}
                """);

        byte[] pdf = service.generate(document);

        assertThat(pdf).startsWith("%PDF".getBytes());
        PdfReader reader = new PdfReader(pdf);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        assertThat(text).contains("制单人：李制单", "审核人：王审核", "客户确认：", "张采购", "2026-08-16 14:30");
        verify(signatureService).find(31L);
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        var pen = new java.awt.geom.Path2D.Float();
        pen.moveTo(8, 16); pen.lineTo(31, 9); pen.lineTo(22, 28); pen.lineTo(38, 19);
        pen.moveTo(43, 10); pen.lineTo(38, 31); pen.lineTo(59, 21); pen.lineTo(53, 8);
        pen.moveTo(69, 11); pen.lineTo(64, 28); pen.lineTo(91, 16); pen.lineTo(83, 30); pen.lineTo(111, 26);
        graphics.draw(pen);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
