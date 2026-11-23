package com.tradepass.module.contract.service.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.money.TradeTaxPricing.Amounts;
import com.tradepass.framework.common.money.TradeTaxPricing.Basis;
import com.tradepass.framework.common.money.TradeTaxPricing.Treatment;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO;
import com.tradepass.module.contract.api.contract.dto.ContractRespDTO.PartySigningProgress;
import com.tradepass.module.contract.service.contract.ContractAmountText;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.CompanyReader.*;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContractPdfServiceImpl implements ContractPdfService {
    private static final String FONT_RESOURCE =
            "/fonts/ttf/NotoSansSC/NotoSansSC-Regular.ttf";
    private static final Color HEADER_BACKGROUND = Color.WHITE;
    private static final Color BORDER_COLOR = new Color(35, 35, 35);
    private static final List<String> DEFAULT_COLUMNS = List.of(
            "名称", "规格型号", "单位", "数量", "单价", "金额", "备注");

    private final ObjectMapper objectMapper;
    private final CompanyReader companyMapper;
    private volatile byte[] fontBytes;

    public ContractPdfServiceImpl(ObjectMapper objectMapper, CompanyReader companyMapper) {
        this.objectMapper = objectMapper;
        this.companyMapper = companyMapper;
    }

    public byte[] generate(ContractRespDTO contract) {
        PartySigningProgress progress = contract.partySigningProgress();
        boolean showProgress = "PENDING".equals(contract.status()) && progress != null
                && (Boolean.TRUE.equals(progress.supplierSigned()) || Boolean.TRUE.equals(progress.buyerSigned()));
        return render(contract, null, showProgress ? progress : null);
    }

    public SigningDocument generateForSigning(ContractRespDTO contract) {
        Map<String, com.tradepass.framework.fadada.core.FadadaSigningGateway.SealPosition> positions = new LinkedHashMap<>();
        byte[] pdf = render(contract, positions, null);
        if (positions.size() != 2 || !positions.containsKey("supplier") || !positions.containsKey("buyer")) {
            throw new BusinessException("合同 PDF 签章位置未能确认，请稍后重试");
        }
        return new SigningDocument(pdf, positions.get("supplier"), positions.get("buyer"));
    }

    private byte[] render(ContractRespDTO contract,
            Map<String, com.tradepass.framework.fadada.core.FadadaSigningGateway.SealPosition> positions,
            PartySigningProgress progress) {
        ContractContent content = parseContent(contract);
        CompanyRespDTO initiator = selectCompany(contract.companyId());
        CompanyRespDTO counterparty = selectCompany(contract.counterpartyCompanyId());
        PartyPair parties = resolveParties(contract, content.fields(), initiator, counterparty);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 42, 42, 46, 42);
            PdfWriter writer = PdfWriter.getInstance(document, output);
            BaseFont baseFont = loadBaseFont();
            Font titleFont = new Font(baseFont, 18, Font.NORMAL, Color.BLACK);
            Font headingFont = new Font(baseFont, 10.5f, Font.NORMAL, Color.BLACK);
            Font signatureTitleFont = new Font(baseFont, 14, Font.NORMAL, Color.BLACK);
            Font bodyFont = new Font(baseFont, 9.5f, Font.NORMAL, Color.BLACK);
            Font tableFont = new Font(baseFont, 8.5f, Font.NORMAL, Color.BLACK);

            document.addTitle(safe(content.title(), "购销合同"));
            document.addSubject(safe(content.title(), "购销合同") + " PDF");
            document.addCreator("TradePass");
            document.open();

            addTitle(document, safe(content.title(), "购销合同"), titleFont);
            if (progress != null) {
                Paragraph note = new Paragraph("签署进度预览（电子签章以归档 PDF 为准）", bodyFont);
                note.setAlignment(Element.ALIGN_CENTER);
                note.setSpacingAfter(10);
                document.add(note);
            }
            addHeader(document, contract, content, parties, bodyFont);
            addProducts(document, contract, content.table(), content.pricing(), headingFont, tableFont, bodyFont);
            addFees(document, contract, content.fees(), content.pricing(), headingFont, tableFont, bodyFont);
            if (content.pricing() != null) {
                addPricingSummary(document, content.pricing().amounts(), tableFont);
            }
            addClauses(document, content.clauses(), contract.terms(), headingFont, bodyFont);
            addSignatureTable(document, parties, signatureTitleFont, tableFont, writer, positions, progress);

            document.close();
            return output.toByteArray();
        } catch (RuntimeException | java.io.IOException exception) {
            throw new BusinessException("合同 PDF 生成失败，请稍后重试");
        }
    }

    public String fileName(ContractRespDTO contract) {
        String name = safe(contract.name(), "购销合同")
                .replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_")
                .trim();
        return (name.isBlank() ? "购销合同" : name) + ".pdf";
    }

    private BaseFont loadBaseFont() throws IOException {
        byte[] bytes = fontBytes;
        if (bytes == null) {
            synchronized (this) {
                bytes = fontBytes;
                if (bytes == null) {
                    try (InputStream stream = ContractPdfService.class.getResourceAsStream(FONT_RESOURCE)) {
                        if (stream == null) {
                            throw new IOException("PDF font resource is missing");
                        }
                        bytes = stream.readAllBytes();
                        fontBytes = bytes;
                    }
                }
            }
        }
        return BaseFont.createFont(
                "NotoSansSC-Regular.ttf",
                BaseFont.IDENTITY_H,
                BaseFont.EMBEDDED,
                true,
                bytes,
                null);
    }

    private void addTitle(Document document, String title, Font font) throws DocumentException {
        String spacedTitle = title.length() <= 6
                ? String.join("　", title.split(""))
                : title;
        Paragraph paragraph = new Paragraph(spacedTitle, font);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        paragraph.setSpacingBefore(50);
        paragraph.setSpacingAfter(10);
        document.add(paragraph);
    }

    private void addHeader(Document document, ContractRespDTO contract, ContractContent content,
                           PartyPair parties, Font font) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{68, 32});
        table.setSpacingAfter(4);
        table.addCell(borderlessCell(
                "供方：" + parties.supplier().displayName() + "\n"
                        + "需方：" + parties.buyer().displayName(), font, Element.ALIGN_LEFT));
        table.addCell(borderlessCell(
                "合同编号：" + safe(contract.contractNo(), contract.id()) + "\n"
                        + "日期：" + resolveDate(contract, content.fields()), font, Element.ALIGN_LEFT));
        document.add(table);
    }

    private void addProducts(Document document, ContractRespDTO contract, ProductTable source, TaxSnapshot pricing,
                             Font headingFont, Font tableFont, Font bodyFont) throws DocumentException {
        Paragraph heading = new Paragraph(stripNumberPrefix(safe(source.title(), "商品明细")), headingFont);
        heading.setSpacingAfter(3);
        document.add(heading);

        List<String> columns = normalizedColumns(source.columns());
        List<List<String>> rows = normalizedRows(source.rows(), columns.size());
        if (pricing != null) {
            applyProductTaxColumns(columns, rows, pricing);
        }
        while (rows.size() < 6) {
            rows.add(new ArrayList<>(java.util.Collections.nCopies(columns.size(), "")));
        }

        PdfPTable table = new PdfPTable(columns.size());
        table.setWidthPercentage(100);
        table.setWidths(columnWidths(columns.size()));
        table.setSplitLate(false);
        table.setSplitRows(true);

        for (String column : columns) {
            table.addCell(tableCell(column, tableFont, Element.ALIGN_CENTER, HEADER_BACKGROUND, 22));
        }
        for (List<String> row : rows) {
            for (int index = 0; index < columns.size(); index++) {
                int alignment = index >= Math.max(3, columns.size() - 4)
                        ? Element.ALIGN_RIGHT : Element.ALIGN_CENTER;
                table.addCell(tableCell(valueAt(row, index), tableFont, alignment, Color.WHITE, 21));
            }
        }

        int quantityIndex = findColumn(columns, "数量");
        int amountIndex = findColumn(columns, "金额");
        if (pricing != null && amountIndex < 0) amountIndex = findColumn(columns, "价税合计");
        BigDecimal quantity = sumColumn(source.rows(), quantityIndex);
        BigDecimal amount = parseDecimal(source.totalAmount(), contract.amount());
        if (pricing != null) {
            amount = amountIndex >= 0 ? sumColumn(rows, amountIndex) : pricing.products().values().stream()
                    .map(TaxLine::quotedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        int labelSpan = Math.min(3, columns.size());
        if (quantityIndex >= 0) labelSpan = Math.min(labelSpan, Math.max(1, quantityIndex));
        if (amountIndex >= 0) labelSpan = Math.min(labelSpan, Math.max(1, amountIndex));
        String totalLabelText = pricing == null ? "合计" : "商品小计";
        if (amountIndex <= 0) totalLabelText += "：¥" + formatDecimal(amount);
        if (quantityIndex == 0) totalLabelText += "\n数量：" + formatDecimal(quantity);

        PdfPCell totalLabel = tableCell(totalLabelText, tableFont, Element.ALIGN_CENTER, Color.WHITE, 22);
        totalLabel.setColspan(labelSpan);
        table.addCell(totalLabel);
        for (int index = labelSpan; index < columns.size(); index++) {
            String value = "";
            if (index == quantityIndex && quantity.compareTo(BigDecimal.ZERO) != 0) {
                value = formatDecimal(quantity);
            }
            if (index == amountIndex) {
                value = formatDecimal(amount);
            }
            table.addCell(tableCell(value, tableFont, Element.ALIGN_RIGHT, Color.WHITE, 22));
        }
        if (pricing != null) {
            document.add(table);
            return;
        }
        String uppercase = safe(source.totalAmountCn(), ContractAmountText.uppercase(amount));
        PdfPCell uppercaseLabel = tableCell(
                columns.size() == 1 ? "合计大写：" + uppercase : "合计大写：",
                bodyFont, Element.ALIGN_LEFT, Color.WHITE, 22);
        table.addCell(uppercaseLabel);
        if (columns.size() > 1) {
            PdfPCell uppercaseValue = tableCell(
                    uppercase, bodyFont, Element.ALIGN_LEFT, Color.WHITE, 22);
            uppercaseValue.setColspan(columns.size() - 1);
            table.addCell(uppercaseValue);
        }
        document.add(table);
    }

    private void applyProductTaxColumns(List<String> columns, List<List<String>> rows, TaxSnapshot pricing) {
        int rateColumn = -1;
        String priceLabel = pricing.basis() == Basis.GROSS ? "含税单价" : "不含税单价";
        for (int index = 0; index < columns.size(); index++) {
            String label = columns.get(index);
            if (label.contains("单价")) {
                columns.set(index, priceLabel + "（元）");
            } else if (label.contains("金额") && !label.contains("税")) {
                columns.set(index, quotedAmountLabel(pricing.basis()));
            } else if (label.contains("税率") || label.contains("征收率")) {
                rateColumn = index;
            }
        }
        if (rateColumn < 0) {
            rateColumn = columns.size();
            columns.add("税率/征收率");
            for (List<String> row : rows) row.add("");
        } else {
            columns.set(rateColumn, "税率/征收率");
        }
        for (int index = 0; index < rows.size(); index++) {
            TaxLine line = pricing.products().get(index);
            if (line != null) rows.get(index).set(rateColumn, line.rateLabel());
        }
    }

    private void addClauses(Document document, List<Clause> clauses, String rawTerms,
                            Font headingFont, Font bodyFont) throws DocumentException {
        List<Clause> actual = clauses;
        if (actual.isEmpty() && rawTerms != null && !rawTerms.isBlank() && !looksLikeJson(rawTerms)) {
            actual = List.of(new Clause("合同条款", rawTerms));
        }
        // 合同快照已包含双方确认的完整条款，生成签署文件时不得再补入或重排默认条款。
        int sectionNumber = 1;
        for (Clause clause : actual) {
            String prefix = chineseNumber(sectionNumber++) + "、";
            String title = stripNumberPrefix(safe(clause.title(), "合同条款"));
            String content = safe(clause.content(), "（未约定）");
            Paragraph paragraph = new Paragraph();
            paragraph.setLeading(15);
            paragraph.setKeepTogether(true);
            paragraph.add(new Phrase(prefix + title + "：", headingFont));
            paragraph.add(new Phrase(content, bodyFont));
            document.add(paragraph);
        }
        Paragraph spacer = new Paragraph(" ", bodyFont);
        spacer.setLeading(3);
        document.add(spacer);
    }

    private void addFees(Document document, ContractRespDTO contract, List<FeeItem> fees, TaxSnapshot pricing,
                         Font headingFont, Font tableFont, Font bodyFont) throws DocumentException {
        if (fees == null || fees.isEmpty()) return;
        Paragraph heading = new Paragraph("其他费用（不计入库存商品）", headingFont);
        heading.setSpacingBefore(5);
        heading.setSpacingAfter(3);
        document.add(heading);
        List<String> columns = pricing == null ? List.of("费用类型", "金额（元）", "备注")
                : List.of("费用类型", quotedAmountLabel(pricing.basis()), "税率/征收率", "备注");
        PdfPTable table = new PdfPTable(columns.size());
        table.setWidthPercentage(100);
        table.setWidths(pricing == null ? new float[]{28, 20, 52} : new float[]{25, 25, 15, 35});
        for (String column : columns) {
            table.addCell(tableCell(column, tableFont, Element.ALIGN_CENTER, HEADER_BACKGROUND, 22));
        }
        BigDecimal feeTotal = BigDecimal.ZERO;
        for (int index = 0; index < fees.size(); index++) {
            FeeItem fee = fees.get(index);
            TaxLine line = pricing == null ? null : pricing.fees().get(index);
            BigDecimal amount = line == null ? parseDecimal(fee.amount(), BigDecimal.ZERO) : line.quotedAmount();
            feeTotal = feeTotal.add(amount);
            table.addCell(tableCell(fee.feeType(), tableFont, Element.ALIGN_CENTER, Color.WHITE, 21));
            table.addCell(tableCell(formatDecimal(amount), tableFont, Element.ALIGN_RIGHT, Color.WHITE, 21));
            if (pricing != null) {
                table.addCell(tableCell(line == null ? "" : line.rateLabel(), tableFont,
                        Element.ALIGN_CENTER, Color.WHITE, 21));
            }
            table.addCell(tableCell(fee.remark(), tableFont, Element.ALIGN_LEFT, Color.WHITE, 21));
        }
        PdfPCell label = tableCell("费用小计", tableFont, Element.ALIGN_RIGHT, Color.WHITE, 22);
        table.addCell(label);
        table.addCell(tableCell(formatDecimal(feeTotal), tableFont, Element.ALIGN_RIGHT, Color.WHITE, 22));
        PdfPCell detail = tableCell(pricing == null ? "合同总额：" + formatDecimal(
                contract.amount() == null ? BigDecimal.ZERO : contract.amount()) : "",
                bodyFont, Element.ALIGN_LEFT, Color.WHITE, 22);
        detail.setColspan(columns.size() - 2);
        table.addCell(detail);
        document.add(table);
    }

    private String quotedAmountLabel(Basis basis) {
        return basis == Basis.GROSS ? "含税金额（元）" : "不含税金额（元）";
    }

    private void addPricingSummary(Document document, Amounts amounts, Font tableFont) throws DocumentException {
        PdfPTable summary = new PdfPTable(2);
        summary.setWidthPercentage(100);
        summary.setWidths(new float[]{28, 72});
        summary.setSpacingBefore(5);
        summary.setKeepTogether(true);
        List<String> labels = List.of("不含税金额", "税额", "合同价税合计", "价税合计大写");
        List<String> values = List.of("¥" + formatDecimal(amounts.netAmount()),
                "¥" + formatDecimal(amounts.taxAmount()), "¥" + formatDecimal(amounts.grossAmount()),
                ContractAmountText.uppercase(amounts.grossAmount()));
        for (int index = 0; index < labels.size(); index++) {
            summary.addCell(tableCell(labels.get(index), tableFont, Element.ALIGN_LEFT, Color.WHITE, 21));
            summary.addCell(tableCell(values.get(index), tableFont, Element.ALIGN_RIGHT, Color.WHITE, 21));
        }
        document.add(summary);
    }

    private void addSignatureTable(Document document, PartyPair parties,
                                   Font headingFont, Font tableFont, PdfWriter writer,
            Map<String, com.tradepass.framework.fadada.core.FadadaSigningGateway.SealPosition> positions,
            PartySigningProgress progress) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50, 50});
        table.setKeepTogether(true);

        table.addCell(tableCell("供方", headingFont, Element.ALIGN_CENTER, HEADER_BACKGROUND, 22));
        table.addCell(tableCell("需方", headingFont, Element.ALIGN_CENTER, HEADER_BACKGROUND, 22));

        Map<String, String> supplier = partyFields(parties.supplier());
        Map<String, String> buyer = partyFields(parties.buyer());
        for (String label : supplier.keySet()) {
            table.addCell(tableCell(label + "：" + supplier.get(label),
                    tableFont, Element.ALIGN_LEFT, Color.WHITE, 17));
            table.addCell(tableCell(label + "：" + buyer.get(label),
                    tableFont, Element.ALIGN_LEFT, Color.WHITE, 17));
        }
        String supplierLabel = progress == null ? "供方盖章" : signatureProgressLabel("供方", progress.supplierSigned());
        String buyerLabel = progress == null ? "需方盖章" : signatureProgressLabel("需方", progress.buyerSigned());
        table.addCell(signatureCell(supplierLabel, "supplier", tableFont, writer, document, positions));
        table.addCell(signatureCell(buyerLabel, "buyer", tableFont, writer, document, positions));
        document.add(table);
    }

    private String signatureProgressLabel(String party, Boolean signed) {
        if (signed == null) return party + "签署状态待同步";
        return party + (signed ? "已签署" : "待签署");
    }

    private PdfPCell signatureCell(String label, String actorId, Font font, PdfWriter writer, Document document,
            Map<String, com.tradepass.framework.fadada.core.FadadaSigningGateway.SealPosition> positions) {
        PdfPCell cell = tableCell(label, font, Element.ALIGN_CENTER, Color.WHITE, 88);
        if (positions != null) cell.setCellEvent((renderedCell, rectangle, canvases) -> {
            // OpenPDF uses 72 points/inch and a bottom-left origin. The provider
            // requires 96 pixels/inch and the center relative to the page's top-left.
            var position = new com.tradepass.framework.fadada.core.FadadaSigningGateway.SealPosition(
                    writer.getPageNumber(), pixels(rectangle.getLeft() + rectangle.getWidth() / 2),
                    pixels(document.getPageSize().getHeight() - rectangle.getBottom() - rectangle.getHeight() / 2));
            position.validate();
            if (positions.putIfAbsent(actorId, position) != null) {
                throw new BusinessException("合同 PDF 签章区域重复，请检查合同内容");
            }
        });
        return cell;
    }

    private String pixels(float points) {
        return java.math.BigDecimal.valueOf(points).multiply(java.math.BigDecimal.valueOf(4))
                .divide(java.math.BigDecimal.valueOf(3), 3, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private ContractContent parseContent(ContractRespDTO contract) {
        String title = safe(contract.name(), "购销合同");
        Map<String, String> fields = new LinkedHashMap<>();
        ProductTable table = ProductTable.empty();
        List<FeeItem> fees = new ArrayList<>();
        List<Clause> clauses = new ArrayList<>();
        TaxSnapshot pricing = null;
        String terms = contract.terms();
        if (terms == null || terms.isBlank()) {
            return new ContractContent(title, fields, table, fees, clauses, null);
        }
        try {
            JsonNode root = objectMapper.readTree(terms);
            if (root == null || !root.isObject()) {
                return new ContractContent(title, fields, table, fees, clauses, null);
            }
            pricing = parseTaxSnapshot(root.path("taxPricing"));
            if (contract.name() == null || contract.name().isBlank()) {
                title = text(root.path("title"), title);
            }
            JsonNode fieldNodes = root.path("fields");
            if (fieldNodes.isArray()) {
                for (JsonNode field : fieldNodes) {
                    String key = text(field.path("key"), "");
                    if (!key.isBlank()) {
                        fields.put(key, text(field.path("value"), ""));
                    }
                }
            }
            JsonNode sectionNodes = root.path("sections");
            if (sectionNodes.isArray()) {
                for (JsonNode section : sectionNodes) {
                    String type = text(section.path("type"), "");
                    if ("table".equals(type) && table.columns().isEmpty()) {
                        table = parseTable(section);
                    } else if ("fees".equals(type)) {
                        for (JsonNode item : section.path("items")) {
                            fees.add(new FeeItem(
                                    text(item.path("feeType"), text(item.path("name"), "其他费用")),
                                    text(item.path("amount"), "0"),
                                    text(item.path("remark"), "")));
                        }
                    } else if ("clause".equals(type)) {
                        clauses.add(new Clause(
                                text(section.path("title"), "合同条款"),
                                text(section.path("content"), "")));
                    }
                }
            }
        } catch (BusinessException invalid) {
            throw invalid;
        } catch (Exception ignored) {
            // 兼容历史纯文本合同条款。
        }
        return new ContractContent(title, fields, table, fees, clauses, pricing);
    }

    private TaxSnapshot parseTaxSnapshot(JsonNode pricing) {
        if (pricing.isMissingNode() || pricing.isNull()) return null;
        try {
            Basis basis = Basis.valueOf(pricing.path("basis").asText());
            Amounts amounts = new Amounts(new BigDecimal(pricing.path("netAmount").asText()),
                    new BigDecimal(pricing.path("taxAmount").asText()), new BigDecimal(pricing.path("grossAmount").asText()));
            Map<Integer, TaxLine> products = new LinkedHashMap<>();
            Map<Integer, TaxLine> fees = new LinkedHashMap<>();
            for (JsonNode line : pricing.path("lines")) {
                Treatment treatment = Treatment.valueOf(line.path("taxTreatment").asText());
                String rateLabel = switch (treatment) {
                    case TAXABLE, SIMPLE -> line.path("taxRate").asText() + "%";
                    case EXEMPT -> "免税";
                    case ZERO_RATE -> "0%（零税率）";
                    case OUT_OF_SCOPE -> "不征税";
                };
                String amountKey = basis == Basis.GROSS ? "grossAmount" : "netAmount";
                TaxLine parsed = new TaxLine(rateLabel, new BigDecimal(line.path(amountKey).asText()));
                ("FEE".equals(line.path("lineType").asText()) ? fees : products)
                        .put(line.path("rowIndex").intValue(), parsed);
            }
            return new TaxSnapshot(basis, amounts, products, fees);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("合同价税快照格式错误");
        }
    }

    private ProductTable parseTable(JsonNode section) {
        List<String> columns = new ArrayList<>();
        JsonNode columnNodes = section.path("columns");
        if (columnNodes.isArray()) {
            columnNodes.forEach(node -> columns.add(text(node, "")));
        }
        List<List<String>> rows = new ArrayList<>();
        JsonNode rowNodes = section.path("rows");
        if (rowNodes.isArray()) {
            for (JsonNode rowNode : rowNodes) {
                List<String> row = new ArrayList<>();
                if (rowNode.isArray()) {
                    rowNode.forEach(node -> row.add(text(node, "")));
                }
                rows.add(row);
            }
        }
        JsonNode summary = section.path("summary");
        return new ProductTable(
                text(section.path("title"), "商品明细"),
                columns,
                rows,
                text(summary.path("totalAmount"), ""),
                text(summary.path("totalAmountCn"), ""));
    }

    private PartyPair resolveParties(ContractRespDTO contract, Map<String, String> fields,
                                     CompanyRespDTO initiator, CompanyRespDTO counterparty) {
        PartyDetails initiatorParty = PartyDetails.from(initiator, fallbackCompanyName(initiator, "本方企业"));
        PartyDetails counterpartyParty = PartyDetails.from(
                counterparty, fallbackCompanyName(counterparty, contract.counterpartyName()));
        boolean purchase = "PURCHASE".equalsIgnoreCase(contract.direction());

        PartyDetails supplier = purchase ? counterpartyParty : initiatorParty;
        PartyDetails buyer = purchase ? initiatorParty : counterpartyParty;
        supplier = supplier.withDisplayName(safe(fields.get("supplier"), supplier.displayName()));
        buyer = buyer.withDisplayName(safe(fields.get("buyer"), buyer.displayName()));
        return new PartyPair(supplier, buyer);
    }

    private CompanyRespDTO selectCompany(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return companyMapper.selectById(Long.parseLong(id));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, String> partyFields(PartyDetails party) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("单位名称（章）", party.displayName());
        fields.put("统一社会信用代码", safe(party.creditCode(), ""));
        fields.put("地址", safe(party.address(), ""));
        fields.put("法定代表人", safe(party.legalPerson(), ""));
        fields.put("委托代理人", "");
        fields.put("电话", safe(party.phone(), ""));
        fields.put("开户银行", safe(party.bankName(), ""));
        fields.put("账号", safe(party.bankAccount(), ""));
        return fields;
    }

    private List<String> normalizedColumns(List<String> source) {
        // Preserve the exact agreed columns; a six-column custom layout may have
        // intentionally removed remarks or any other default column.
        return source == null || source.isEmpty()
                ? new ArrayList<>(DEFAULT_COLUMNS)
                : new ArrayList<>(source);
    }

    private List<List<String>> normalizedRows(List<List<String>> source, int columnCount) {
        List<List<String>> result = new ArrayList<>();
        if (source != null) {
            for (List<String> sourceRow : source) {
                List<String> row = new ArrayList<>(sourceRow == null ? List.of() : sourceRow);
                while (row.size() < columnCount) {
                    row.add("");
                }
                result.add(row);
            }
        }
        return result;
    }

    private float[] columnWidths(int count) {
        if (count == 7) {
            return new float[]{15, 23, 9, 11, 12, 16, 14};
        }
        float[] widths = new float[count];
        java.util.Arrays.fill(widths, 1f);
        return widths;
    }

    private PdfPCell borderlessCell(String value, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_TOP);
        cell.setPadding(1);
        cell.setLeading(4, 1.25f);
        return cell;
    }

    private PdfPCell tableCell(String value, Font font, int alignment, Color background, float minimumHeight) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value, ""), font));
        cell.setBorderColor(BORDER_COLOR);
        cell.setBorderWidth(0.65f);
        cell.setBackgroundColor(background);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setMinimumHeight(minimumHeight);
        cell.setPaddingLeft(3);
        cell.setPaddingRight(3);
        cell.setPaddingTop(2);
        cell.setPaddingBottom(2);
        return cell;
    }

    private int findColumn(List<String> columns, String keyword) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).contains(keyword)) {
                return index;
            }
        }
        return -1;
    }

    private BigDecimal sumColumn(List<List<String>> rows, int index) {
        BigDecimal result = BigDecimal.ZERO;
        if (rows == null) {
            return result;
        }
        for (List<String> row : rows) {
            try {
                result = result.add(new BigDecimal(valueAt(row, index).trim()));
            } catch (Exception ignored) {
                // 非数字数量不参与合计。
            }
        }
        return result;
    }

    private BigDecimal parseDecimal(String value, BigDecimal fallback) {
        try {
            return new BigDecimal(value);
        } catch (Exception ignored) {
            return fallback == null ? BigDecimal.ZERO : fallback;
        }
    }

    private String formatDecimal(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String resolveDate(ContractRespDTO contract, Map<String, String> fields) {
        String date = fields.get("signDate");
        if (date == null || date.isBlank()) {
            date = contract.startDate();
        }
        if ((date == null || date.isBlank()) && contract.createdAt() != null) {
            date = contract.createdAt().length() >= 10
                    ? contract.createdAt().substring(0, 10) : contract.createdAt();
        }
        return safe(date, LocalDate.now().toString());
    }

    private String fallbackCompanyName(CompanyRespDTO company, String fallback) {
        return company == null ? safe(fallback, "") : safe(company.getName(), fallback);
    }

    private String text(JsonNode node, String fallback) {
        return node == null || node.isNull() || node.isMissingNode()
                ? fallback : safe(node.asText(), fallback);
    }

    private String valueAt(List<String> values, int index) {
        return values == null || index < 0 || index >= values.size()
                ? "" : safe(values.get(index), "");
    }

    private String stripNumberPrefix(String value) {
        return value.replaceFirst("^\\s*(?:第[一二三四五六七八九十百零0-9]+条[、.．:：]?\\s*|[一二三四五六七八九十百零0-9]+[、.．)）]\\s*)", "");
    }

    private boolean looksLikeJson(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String chineseNumber(int value) {
        String[] numbers = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        if (value <= 10) {
            return numbers[value];
        }
        if (value < 20) {
            return "十" + numbers[value - 10];
        }
        int tens = value / 10;
        int units = value % 10;
        return numbers[tens] + "十" + (units == 0 ? "" : numbers[units]);
    }

    private record ContractContent(
            String title,
            Map<String, String> fields,
            ProductTable table,
            List<FeeItem> fees,
            List<Clause> clauses,
            TaxSnapshot pricing
    ) {
    }

    private record ProductTable(
            String title,
            List<String> columns,
            List<List<String>> rows,
            String totalAmount,
            String totalAmountCn
    ) {
        private static ProductTable empty() {
            return new ProductTable("商品明细", List.of(), List.of(), "", "");
        }
    }

    private record Clause(String title, String content) {
    }

    private record FeeItem(String feeType, String amount, String remark) {
    }

    private record TaxSnapshot(Basis basis, Amounts amounts, Map<Integer, TaxLine> products, Map<Integer, TaxLine> fees) {
    }

    private record TaxLine(String rateLabel, BigDecimal quotedAmount) {
    }

    private record PartyPair(PartyDetails supplier, PartyDetails buyer) {
    }

    private record PartyDetails(
            String displayName,
            String creditCode,
            String address,
            String legalPerson,
            String phone,
            String bankName,
            String bankAccount
    ) {
        private static PartyDetails from(CompanyRespDTO company, String fallbackName) {
            if (company == null) {
                return new PartyDetails(fallbackName, "", "", "", "", "", "");
            }
            return new PartyDetails(
                    company.getName(),
                    company.getCreditCode(),
                    company.getRegisteredAddress(),
                    company.getLegalPersonName(),
                    company.getContactPhone(),
                    company.getBankName(),
                    company.getBankAccount());
        }

        private PartyDetails withDisplayName(String name) {
            return new PartyDetails(name, creditCode, address, legalPerson, phone, bankName, bankAccount);
        }
    }

}
