package com.tradepass.module.trade.service.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.money.TradeTaxPricing;
import com.tradepass.framework.common.money.TradeTaxPricing.Amounts;
import com.tradepass.framework.common.money.TradeTaxPricing.Basis;
import com.tradepass.framework.common.money.TradeTaxPricing.Treatment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Immutable pricing captured on a contract or a business document. */
public final class DocumentTaxPricing {
    private static final Amounts ZERO = new Amounts(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");

    private DocumentTaxPricing() {
    }

    public record Line(int rowIndex, String lineType, String lineId, String sourceLineId, Treatment treatment,
                       BigDecimal taxRate, BigDecimal quantity, BigDecimal quoteUnitPrice, Amounts amounts) {
        public String contractLineId() {
            return sourceLineId == null ? lineId : sourceLineId;
        }

        public String taxDisplay() {
            return switch (treatment) {
                case EXEMPT -> "免税";
                case ZERO_RATE -> "0%（零税率）";
                case OUT_OF_SCOPE -> "不征税";
                case TAXABLE, SIMPLE -> taxRate.stripTrailingZeros().toPlainString() + "%";
            };
        }
    }

    public record Snapshot(Basis basis, Integer contractVersion, Long sourceDocumentId,
                           List<Line> lines, Amounts amounts) {
    }

    public static Snapshot read(ObjectMapper mapper, String documentContent) {
        if (documentContent == null || !documentContent.stripLeading().startsWith("{")) return null;
        try {
            return read(mapper.readTree(documentContent).get("taxPricing"));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("单据价税数据格式不正确");
        }
    }

    public static Snapshot read(JsonNode pricing) {
        if (pricing == null || pricing.isNull()) return null;
        if (!pricing.isObject() || pricing.path("version").asInt() != 1 || !pricing.path("lines").isArray()) {
            throw new BusinessException("单据价税数据格式不正确");
        }
        try {
            List<Line> lines = new ArrayList<>();
            for (JsonNode line : pricing.path("lines")) {
                String sourceId = line.path("sourceLineId").asText("");
                lines.add(new Line(line.path("rowIndex").asInt(-1), line.path("lineType").asText(),
                        line.path("lineId").asText(), sourceId.isBlank() ? null : sourceId,
                        Treatment.valueOf(line.path("taxTreatment").asText()), decimal(line, "taxRate"),
                        decimal(line, "quantity"), decimal(line, "quoteUnitPrice"), readAmounts(line)));
            }
            return new Snapshot(Basis.valueOf(pricing.path("basis").asText()),
                    pricing.hasNonNull("contractVersion") ? pricing.path("contractVersion").asInt() : null,
                    sourceDocumentId(pricing), List.copyOf(lines), readAmounts(pricing));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("单据价税数据格式不正确");
        }
    }

    static Long sourceDocumentId(JsonNode pricing) {
        if (!pricing.hasNonNull("sourceDocumentId")) return null;
        try {
            long id = Long.parseLong(pricing.path("sourceDocumentId").asText());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException("原销售单编号格式不正确");
        }
    }

    static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.path(field).asText());
    }

    private static Amounts readAmounts(JsonNode node) {
        return new Amounts(decimal(node, "netAmount"), decimal(node, "taxAmount"), decimal(node, "grossAmount"));
    }

    static Amounts calculate(Basis basis, Line source, BigDecimal quantity) {
        try {
            return TradeTaxPricing.calculate(basis, source.treatment(), source.taxRate(), quantity, source.quoteUnitPrice());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(exception.getMessage());
        }
    }

    public static ObjectNode write(ObjectMapper mapper, Basis basis, Integer contractVersion,
                            Long sourceDocumentId, List<Line> lines) {
        ObjectNode result = mapper.createObjectNode();
        result.put("version", 1);
        result.put("basis", basis.name());
        if (contractVersion != null) result.put("contractVersion", contractVersion);
        if (sourceDocumentId != null) result.put("sourceDocumentId", sourceDocumentId.toString());
        ArrayNode children = result.putArray("lines");
        Amounts total = ZERO;
        for (Line item : lines) {
            ObjectNode child = children.addObject();
            child.put("rowIndex", item.rowIndex());
            child.put("lineType", item.lineType());
            child.put("lineId", item.lineId());
            child.put("sourceLineId", item.sourceLineId());
            child.put("taxTreatment", item.treatment().name());
            child.put("taxRate", item.taxRate().toPlainString());
            child.put("quantity", item.quantity().toPlainString());
            child.put("quoteUnitPrice", item.quoteUnitPrice().toPlainString());
            putAmounts(child, item.amounts());
            total = plus(total, item.amounts());
        }
        if (total.grossAmount().compareTo(MAX_AMOUNT) > 0) throw new BusinessException("单据价税合计过大");
        putAmounts(result, total);
        return result;
    }

    private static void putAmounts(ObjectNode node, Amounts amounts) {
        node.put("netAmount", amounts.netAmount().toPlainString());
        node.put("taxAmount", amounts.taxAmount().toPlainString());
        node.put("grossAmount", amounts.grossAmount().toPlainString());
    }

    public static Amounts plus(Amounts first, Amounts second) {
        return new Amounts(first.netAmount().add(second.netAmount()), first.taxAmount().add(second.taxAmount()),
                first.grossAmount().add(second.grossAmount()));
    }

    public static Amounts minus(Amounts first, Amounts second) {
        if (first.netAmount().compareTo(second.netAmount()) < 0
                || first.taxAmount().compareTo(second.taxAmount()) < 0
                || first.grossAmount().compareTo(second.grossAmount()) < 0) {
            throw new BusinessException("累计价税金额超过原销售单");
        }
        return new Amounts(first.netAmount().subtract(second.netAmount()), first.taxAmount().subtract(second.taxAmount()),
                first.grossAmount().subtract(second.grossAmount()));
    }

    public static Amounts refund(Amounts available, BigDecimal quantity, BigDecimal availableQuantity) {
        if (quantity.compareTo(availableQuantity) == 0) return available;
        BigDecimal net = proportional(available.netAmount(), quantity, availableQuantity);
        BigDecimal tax = proportional(available.taxAmount(), quantity, availableQuantity);
        return new Amounts(net, tax, net.add(tax));
    }

    private static BigDecimal proportional(BigDecimal amount, BigDecimal quantity, BigDecimal totalQuantity) {
        return amount.multiply(quantity).divide(totalQuantity, 2, RoundingMode.HALF_UP);
    }
}
