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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessDocumentPdfServiceTest {

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
                 "preparedByName":"李制单","blankRows":1,
                 "columns":["品名","数量","单价","金额"],
                 "rows":[["商品A","2","3.5","7"]]}
                """);

        byte[] pdf = service.generate(document);

        assertThat(pdf).startsWith("%PDF".getBytes());
        PdfReader reader = new PdfReader(pdf);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        assertThat(text).contains("制单人：李制单", "客户确认：", "张采购", "2026-08-16 14:30");
        verify(signatureService).find(31L);
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(15, 20, Color.BLACK.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
