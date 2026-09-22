package com.tradepass.module.settlement.service.reconciliation;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReconciliationPdfServiceTest {
    @Test
    void exportsOnlySelectedDirectionAndBothInvoiceTotals() throws Exception {
        Map<String, Object> account = new HashMap<>();
        account.put("mySalesAmount", "1000"); account.put("myPurchaseAmount", "2000");
        account.put("issuedInvoiceAmount", "400"); account.put("receivedInvoiceAmount", "700");
        account.put("unbilledAmount", "600"); account.put("unreceivedInvoiceAmount", "1300");
        account.put("entries", List.of(
                Map.of("direction", "SALE", "directionText", "我方销售", "documentNo", "SALE-ONLY", "amount", "1000"),
                Map.of("direction", "PURCHASE", "directionText", "我方采购", "documentNo", "BUY-ONLY", "amount", "2000")));
        var service = new ReconciliationPdfServiceImpl(null, null);
        for (String role : List.of("supplier", "buyer")) {
            byte[] pdf = service.generatePdf("示例企业与合作企业对账单", account, role);
            Files.createDirectories(Path.of("target/pdf-preview"));
            Files.write(Path.of("target/pdf-preview/reconciliation-" + role + ".pdf"), pdf);
            try (PdfReader reader = new PdfReader(pdf)) {
                String text = new PdfTextExtractor(reader).getTextFromPage(1);
                assertThat(text).contains("已开发票金额", "未开发票金额");
                if (role.equals("supplier")) assertThat(text).contains("我方销售", "已收款", "SALE-ONLY", "600.00")
                        .doesNotContain("我方采购", "已付款", "BUY-ONLY");
                else assertThat(text).contains("我方采购", "已付款", "BUY-ONLY", "1300.00")
                        .doesNotContain("我方销售", "已收款", "SALE-ONLY");
            }
        }
    }
}
