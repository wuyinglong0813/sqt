package com.tradepass.module.trade.service.document;

import com.tradepass.module.trade.service.inventory.SalesOrderSignatureService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.format.DateTimeFormatter;

@Service
public class BusinessDocumentPdfServiceImpl implements BusinessDocumentPdfService {
    private static final String FONT_RESOURCE = "/fonts/ttf/NotoSansSC/NotoSansSC-Regular.ttf";
    private static final Color BORDER_COLOR = new Color(25, 25, 25);

    private final ObjectMapper objectMapper;
    private final SalesOrderSignatureService signatureService;
    private volatile byte[] fontBytes;

    @Autowired
    public BusinessDocumentPdfServiceImpl(ObjectMapper objectMapper,
                                      SalesOrderSignatureService signatureService) {
        this.objectMapper = objectMapper;
        this.signatureService = signatureService;
    }

    BusinessDocumentPdfServiceImpl(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public byte[] generate(BusinessDocumentDO businessDocument) {
        Snapshot snapshot = parseSnapshot(businessDocument);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 48, 48, 54, 48);
            PdfWriter.getInstance(document, output);
            BaseFont baseFont = loadBaseFont();
            Font titleFont = new Font(baseFont, 17, Font.NORMAL, Color.BLACK);
            Font bodyFont = new Font(baseFont, 9, Font.NORMAL, Color.BLACK);
            Font headerFont = new Font(baseFont, 9.5f, Font.NORMAL, Color.BLACK);
            Font footerFont = new Font(baseFont, 9, Font.NORMAL, Color.BLACK);

            document.addTitle(snapshot.companyName() + snapshot.title());
            document.addSubject(snapshot.title());
            document.addCreator("TradePass");
            document.open();

            Paragraph title = new Paragraph(snapshot.companyName() + snapshot.title(), titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingBefore(22);
            title.setSpacingAfter(10);
            document.add(title);

            if ("DRAFT".equals(businessDocument.getStatus())) {
                Font draftFont = new Font(baseFont, 10, Font.NORMAL, new Color(180, 106, 0));
                Paragraph draft = new Paragraph(documentLabel(businessDocument) + "草稿 · 合同生效并发布后方为正式单据", draftFont);
                draft.setAlignment(Element.ALIGN_CENTER);
                draft.setSpacingAfter(10);
                document.add(draft);
            }

            addDocumentHeader(document, businessDocument, snapshot, bodyFont);
            addProductTable(document, snapshot, headerFont, bodyFont);
            SalesOrderSignatureService.Confirmation confirmation = signatureService == null
                    ? null : signatureService.find(businessDocument.getId());
            addFooter(document, businessDocument.getDocumentType(), snapshot, footerFont, confirmation);

            document.close();
            return output.toByteArray();
        } catch (IOException | DocumentException exception) {
            throw new BusinessException("单据 PDF 生成失败，请稍后重试");
        }
    }

    public String fileName(BusinessDocumentDO document) {
        String title = documentLabel(document) + ("DRAFT".equals(document.getStatus()) ? "草稿" : "");
        return (title + "-" + document.getDocumentNo())
                .replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_") + ".pdf";
    }

    private String documentLabel(BusinessDocumentDO document) {
        return document != null && "RETURN_ORDER".equals(document.getDocumentType())
                ? "退货单" : "销售单";
    }

    private void addDocumentHeader(Document document, BusinessDocumentDO source,
                                   Snapshot snapshot, Font font) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{62, 38});
        table.setSpacingAfter(4);
        table.addCell(borderlessCell("往来单位：" + snapshot.counterpartyName()
                + "\n关联合同：" + snapshot.contractNo(), font, Element.ALIGN_LEFT));
        table.addCell(borderlessCell("单据编号：" + source.getDocumentNo()
                + "\n日期：" + snapshot.date(), font, Element.ALIGN_LEFT));
        document.add(table);
    }

    private void addProductTable(Document document, Snapshot snapshot,
                                 Font headerFont, Font bodyFont) throws DocumentException {
        int columnCount = snapshot.columns().size();
        PdfPTable table = new PdfPTable(columnCount);
        table.setWidthPercentage(100);
        table.setWidths(columnWidths(snapshot.columns()));
        table.setSplitLate(false);
        table.setSplitRows(true);

        for (String column : snapshot.columns()) {
            table.addCell(tableCell(column, headerFont, Element.ALIGN_CENTER, 25));
        }
        for (List<String> row : snapshot.rows()) {
            for (int index = 0; index < columnCount; index++) {
                String value = index < row.size() ? row.get(index) : "";
                int alignment = isNumericColumn(snapshot.columns().get(index))
                        ? Element.ALIGN_RIGHT : Element.ALIGN_CENTER;
                table.addCell(tableCell(value, bodyFont, alignment, 25));
            }
        }
        int targetRows = Math.max(snapshot.blankRows(), snapshot.rows().size());
        for (int rowIndex = snapshot.rows().size(); rowIndex < targetRows; rowIndex++) {
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                table.addCell(tableCell("", bodyFont, Element.ALIGN_CENTER, 25));
            }
        }
        document.add(table);
    }

    private void addFooter(Document document, String type, Snapshot snapshot,
                           Font font, SalesOrderSignatureService.Confirmation confirmation)
            throws DocumentException {
        Paragraph total = new Paragraph("合计金额（元）：" + snapshot.totalAmount(), font);
        total.setAlignment(Element.ALIGN_RIGHT);
        total.setSpacingBefore(8);
        document.add(total);
        PdfPTable signatures = new PdfPTable(3);
        signatures.setWidthPercentage(100);
        signatures.setSpacingBefore(28);
        signatures.addCell(borderlessCell("制单人：" + snapshot.preparedByName(), font, Element.ALIGN_LEFT));
        signatures.addCell(borderlessCell("审核人：", font, Element.ALIGN_CENTER));
        signatures.addCell(customerSignatureCell(confirmation, font));
        document.add(signatures);
    }

    private PdfPCell customerSignatureCell(SalesOrderSignatureService.Confirmation confirmation,
                                            Font font) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setPadding(1);
        Paragraph label = new Paragraph("客户确认：", font);
        label.setAlignment(Element.ALIGN_RIGHT);
        cell.addElement(label);
        if (confirmation == null || confirmation.data() == null || confirmation.data().length == 0) {
            return cell;
        }
        try {
            Image signature = Image.getInstance(confirmation.data());
            signature.scaleToFit(82, 34);
            signature.setAlignment(Element.ALIGN_RIGHT);
            cell.addElement(signature);
            String time = confirmation.signedAt() == null ? ""
                    : confirmation.signedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            Paragraph signer = new Paragraph(confirmation.signerName() + "  " + time, font);
            signer.setAlignment(Element.ALIGN_RIGHT);
            cell.addElement(signer);
        } catch (Exception exception) {
            throw new BusinessException("销售单签名图片损坏，无法生成 PDF");
        }
        return cell;
    }

    private Snapshot parseSnapshot(BusinessDocumentDO document) {
        try {
            JsonNode root = objectMapper.readTree(document.getContent());
            List<String> columns = new ArrayList<>();
            root.path("columns").forEach(node -> columns.add(node.asText("")));
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("missing columns");
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode rowNode : root.path("rows")) {
                List<String> row = new ArrayList<>();
                rowNode.forEach(node -> row.add(node.asText("")));
                rows.add(row);
            }
            return new Snapshot(
                    root.path("title").asText("销售单"),
                    root.path("companyName").asText("本方企业"),
                    root.path("counterpartyName").asText(""),
                    root.path("contractNo").asText(""),
                    root.path("date").asText(""),
                    root.path("totalAmount").asText("0"),
                    root.path("preparedByName").asText(""),
                    Math.max(1, root.path("blankRows").asInt(10)),
                    columns,
                    rows);
        } catch (Exception exception) {
            throw new BusinessException("单据数据损坏，无法生成 PDF");
        }
    }

    private BaseFont loadBaseFont() throws IOException, DocumentException {
        byte[] bytes = fontBytes;
        if (bytes == null) {
            synchronized (this) {
                bytes = fontBytes;
                if (bytes == null) {
                    try (InputStream stream = BusinessDocumentPdfService.class.getResourceAsStream(FONT_RESOURCE)) {
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

    private PdfPCell borderlessCell(String value, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(value == null ? "" : value, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_TOP);
        cell.setPadding(1);
        cell.setLeading(3, 1.25f);
        return cell;
    }

    private PdfPCell tableCell(String value, Font font, int alignment, float height) {
        PdfPCell cell = new PdfPCell(new Phrase(value == null ? "" : value, font));
        cell.setBorderColor(BORDER_COLOR);
        cell.setBorderWidth(.7f);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setMinimumHeight(height);
        cell.setPadding(3);
        return cell;
    }

    private float[] columnWidths(List<String> columns) {
        float[] widths = new float[columns.size()];
        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            if (column.contains("序号") || column.contains("单位")) {
                widths[index] = .75f;
            } else if (column.contains("品名") || column.contains("名称")) {
                widths[index] = 1.75f;
            } else if (column.contains("规格")) {
                widths[index] = 1.35f;
            } else if (column.contains("备注")) {
                widths[index] = 1.35f;
            } else {
                widths[index] = 1f;
            }
        }
        return widths;
    }

    private boolean isNumericColumn(String column) {
        return Arrays.stream(new String[]{"数量", "单价", "金额"})
                .anyMatch(column::contains);
    }

    private record Snapshot(String title, String companyName, String counterpartyName,
                            String contractNo, String date, String totalAmount,
                            String preparedByName, int blankRows,
                            List<String> columns, List<List<String>> rows) {
    }
}
