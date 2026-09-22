package com.tradepass.module.settlement.service.reconciliation;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class ReconciliationPdfServiceImpl implements ReconciliationPdfService {
    private static final String FONT_RESOURCE = "/fonts/ttf/NotoSansSC/NotoSansSC-Regular.ttf";
    private static final Color BORDER_COLOR = new Color(45, 45, 45);

    private final ReconciliationAccountService accountService;
    private final CompanyReader companyMapper;
    private volatile byte[] fontBytes;

    public ReconciliationPdfServiceImpl(ReconciliationAccountService accountService,
                                    CompanyReader companyMapper) {
        this.accountService = accountService;
        this.companyMapper = companyMapper;
    }

    public PdfRespVO generate(Long counterpartyCompanyId) {
        return generate(counterpartyCompanyId, "supplier");
    }

    public PdfRespVO generate(Long counterpartyCompanyId, String role) {
        Map<String, Object> account = accountService.account(counterpartyCompanyId);
        CompanyRespDTO currentCompany = companyMapper.selectById(AuthContext.requireCompanyId());
        String currentName = currentCompany == null ? "当前企业" : safe(currentCompany.getName());
        String counterpartyName = safe(account.get("counterpartyName"));
        String title = currentName + "与" + counterpartyName + "对账单";
        return new PdfRespVO(safeFileName(title) + ".pdf", generatePdf(title, account, role));
    }

    public byte[] generatePdf(String title, Map<String, Object> account) {
        return generatePdf(title, account, "supplier");
    }

    public byte[] generatePdf(String title, Map<String, Object> account, String role) {
        if (!"supplier".equals(role) && !"buyer".equals(role)) throw new BusinessException("对账视角无效");
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 38, 38, 38, 34);
            PdfWriter.getInstance(document, output);
            BaseFont baseFont = loadBaseFont();
            Font titleFont = new Font(baseFont, 17, Font.NORMAL, Color.BLACK);
            Font bodyFont = new Font(baseFont, 9, Font.NORMAL, Color.BLACK);
            Font smallFont = new Font(baseFont, 8, Font.NORMAL, Color.BLACK);
            document.addTitle(title);
            document.addSubject("企业往来对账明细");
            document.addCreator("TradePass");
            document.open();

            Paragraph heading = new Paragraph(title, titleFont);
            heading.setAlignment(Element.ALIGN_CENTER);
            heading.setSpacingAfter(6);
            document.add(heading);
            Paragraph generated = new Paragraph("生成日期：" + LocalDate.now(), smallFont);
            generated.setAlignment(Element.ALIGN_RIGHT);
            generated.setSpacingAfter(10);
            document.add(generated);

            addSummary(document, account, bodyFont, role);
            addEntries(document, account, bodyFont, smallFont, role);
            document.close();
            return output.toByteArray();
        } catch (IOException | DocumentException exception) {
            throw new BusinessException("对账 PDF 生成失败，请稍后重试");
        }
    }

    private void addSummary(Document document, Map<String, Object> account, Font font, String role)
            throws DocumentException {
        boolean buyer = "buyer".equals(role);
        PdfPTable summary = new PdfPTable(5);
        summary.setWidthPercentage(100);
        summary.setSpacingAfter(12);
        addSummaryCell(summary, buyer ? "我方采购" : "我方销售", account.get(buyer ? "myPurchaseAmount" : "mySalesAmount"), font);
        addSummaryCell(summary, buyer ? "已付款" : "已收款", account.get(buyer ? "paidPaymentAmount" : "receivedPaymentAmount"), font);
        addSummaryCell(summary, buyer ? "应付余额" : "应收余额", account.get(buyer ? "payableBalance" : "receivableBalance"), font);
        addSummaryCell(summary, "已开发票金额", account.get(buyer ? "receivedInvoiceAmount" : "issuedInvoiceAmount"), font);
        addSummaryCell(summary, "未开发票金额", account.get(buyer ? "unreceivedInvoiceAmount" : "unbilledAmount"), font);
        document.add(summary);
    }

    private void addSummaryCell(PdfPTable table, String label, Object amount, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(label + "\n¥" + money(amount), font));
        cell.setBorderColor(BORDER_COLOR);
        cell.setBorderWidth(.6f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setMinimumHeight(38);
        cell.setPadding(5);
        table.addCell(cell);
    }

    @SuppressWarnings("unchecked")
    private void addEntries(Document document, Map<String, Object> account,
                            Font headerFont, Font bodyFont, String role) throws DocumentException {
        Paragraph label = new Paragraph("已确认业务明细", headerFont);
        label.setSpacingAfter(5);
        document.add(label);
        PdfPTable table = new PdfPTable(7);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{12, 17, 12, 21, 12, 13, 13});
        table.setSplitLate(false);
        table.setSplitRows(true);
        for (String header : List.of("业务日期", "合同编号", "资料类型", "单据编号",
                "业务方向", "金额（元）", "确认时间")) {
            table.addCell(tableCell(header, headerFont, Element.ALIGN_CENTER, 25));
        }
        List<Map<String, Object>> entries = account.get("entries") instanceof List<?>
                ? (List<Map<String, Object>>) account.get("entries") : List.of();
        String direction = "buyer".equals(role) ? "PURCHASE" : "SALE";
        entries = entries.stream().filter(entry -> direction.equals(entry.get("direction"))).toList();
        table.setHeaderRows(1);
        if (entries.isEmpty()) {
            PdfPCell empty = tableCell("暂无已确认业务单据", bodyFont, Element.ALIGN_CENTER, 42);
            empty.setColspan(7);
            table.addCell(empty);
        } else {
            for (Map<String, Object> entry : entries) {
                table.addCell(tableCell(safe(entry.get("businessDate")), bodyFont, Element.ALIGN_CENTER, 24));
                table.addCell(tableCell(safe(entry.get("contractNo")), bodyFont, Element.ALIGN_CENTER, 24));
                table.addCell(tableCell(safe(entry.get("sourceTypeText")), bodyFont, Element.ALIGN_CENTER, 24));
                table.addCell(tableCell(safe(entry.get("documentNo")), bodyFont, Element.ALIGN_LEFT, 24));
                table.addCell(tableCell(safe(entry.get("directionText")), bodyFont, Element.ALIGN_CENTER, 24));
                table.addCell(tableCell(money(entry.get("amount")), bodyFont, Element.ALIGN_RIGHT, 24));
                table.addCell(tableCell(shortDateTime(entry.get("approvedAt")), bodyFont, Element.ALIGN_CENTER, 24));
            }
        }
        document.add(table);
    }

    private PdfPCell tableCell(String value, Font font, int alignment, float height) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setBorderColor(BORDER_COLOR);
        cell.setBorderWidth(.6f);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setMinimumHeight(height);
        cell.setPadding(4);
        return cell;
    }

    private BaseFont loadBaseFont() throws IOException, DocumentException {
        byte[] bytes = fontBytes;
        if (bytes == null) {
            synchronized (this) {
                bytes = fontBytes;
                if (bytes == null) {
                    try (InputStream stream = ReconciliationPdfService.class.getResourceAsStream(FONT_RESOURCE)) {
                        if (stream == null) throw new IOException("PDF font resource is missing");
                        bytes = stream.readAllBytes();
                        fontBytes = bytes;
                    }
                }
            }
        }
        return BaseFont.createFont("NotoSansSC-Regular.ttf", BaseFont.IDENTITY_H,
                BaseFont.EMBEDDED, true, bytes, null);
    }

    private String shortDateTime(Object value) {
        String text = safe(value).replace('T', ' ');
        return text.length() > 16 ? text.substring(0, 16) : text;
    }

    private String money(Object value) {
        if (value == null) return "0.00";
        try {
            return new java.math.BigDecimal(String.valueOf(value))
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException exception) {
            return "0.00";
        }
    }

    private String safeFileName(String value) {
        return safe(value).replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    
}
