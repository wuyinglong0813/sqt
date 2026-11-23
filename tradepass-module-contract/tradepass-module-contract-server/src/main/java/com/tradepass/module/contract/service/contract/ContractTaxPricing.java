package com.tradepass.module.contract.service.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.money.TradeTaxPricing;
import com.tradepass.framework.common.money.TradeTaxPricing.Amounts;
import com.tradepass.framework.common.money.TradeTaxPricing.Basis;
import com.tradepass.framework.common.money.TradeTaxPricing.Treatment;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class ContractTaxPricing {
    private static final Pattern UNSIGNED_DECIMAL = Pattern.compile("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    private static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999999.99");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final Amounts ZERO = new Amounts(new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"));

    private ContractTaxPricing() {
    }

    static BigDecimal normalize(ObjectNode terms) {
        JsonNode requested = terms.get("taxPricing");
        if (requested == null || requested.isNull()) {
            terms.remove("taxPricing");
            return null;
        }
        if (!requested.isObject() || !requested.path("version").isIntegralNumber()
                || !requested.path("version").canConvertToInt() || requested.path("version").intValue() != 1
                || !(requested.get("lines") instanceof ArrayNode requestedLines)
                || !(terms.get("sections") instanceof ArrayNode sections)) {
            throw new BusinessException("合同价税明细格式错误");
        }
        Basis basis = readBasis(requested.path("basis").asText());
        Map<String, JsonNode> pendingLines = indexLines(requestedLines);
        ObjectNode pricing = terms.putObject("taxPricing");
        pricing.put("version", 1);
        pricing.put("basis", basis.name());
        ArrayNode lines = pricing.putArray("lines");

        Amounts total = ZERO;
        boolean productTableSeen = false;
        int feeIndex = 0;
        for (JsonNode entry : sections) {
            if (!(entry instanceof ObjectNode section)) {
                throw new BusinessException("合同明细格式错误");
            }
            switch (section.path("type").asText()) {
                case "table" -> {
                    if (productTableSeen) {
                        throw new BusinessException("价税合同只支持一张商品明细表");
                    }
                    productTableSeen = true;
                    Amounts products = normalizeProducts(section, basis, lines, pendingLines);
                    total = add(total, products);
                }
                case "fees" -> {
                    if (!(section.get("items") instanceof ArrayNode fees)) {
                        throw new BusinessException("合同费用明细格式错误");
                    }
                    Amounts sectionTotal = ZERO;
                    for (JsonNode fee : fees) {
                        if (!fee.isObject()) {
                            throw new BusinessException("合同费用明细格式错误");
                        }
                        BigDecimal price = decimal(fee.path("amount"), "费用金额");
                        String id = "FEE:" + feeIndex;
                        Amounts amounts = calculateLine(lines, pendingLines.remove(id), "FEE", feeIndex, basis, ONE, price);
                        sectionTotal = add(sectionTotal, amounts);
                        feeIndex++;
                    }
                    updateSectionSummary(section, sectionTotal);
                    total = add(total, sectionTotal);
                }
                default -> {
                    // Contract clauses and other non-priced sections do not contribute to the amount.
                }
            }
        }
        if (!pendingLines.isEmpty()) {
            throw new BusinessException("合同价税明细与商品或费用不一致，请重新填写");
        }
        putAmounts(pricing, total);
        return total.grossAmount();
    }

    private static Map<String, JsonNode> indexLines(ArrayNode lines) {
        Map<String, JsonNode> indexed = new HashMap<>();
        for (JsonNode line : lines) {
            String type = line.path("lineType").asText();
            JsonNode index = line.path("rowIndex");
            if ((!type.equals("PRODUCT") && !type.equals("FEE"))
                    || !index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0) {
                throw new BusinessException("合同价税行号无效");
            }
            if (indexed.putIfAbsent(type + ":" + index.intValue(), line) != null) {
                throw new BusinessException("合同价税行号重复");
            }
        }
        return indexed;
    }

    private static Amounts normalizeProducts(ObjectNode section, Basis basis, ArrayNode lines,
                                              Map<String, JsonNode> pendingLines) {
        if (!(section.get("columns") instanceof ArrayNode columns)
                || !(section.get("rows") instanceof ArrayNode rows)) {
            throw new BusinessException("合同商品明细格式错误");
        }
        int productIndex = -1;
        int quantityIndex = -1;
        int priceIndex = -1;
        for (int index = 0; index < columns.size(); index++) {
            String label = columns.path(index).asText();
            if (productIndex < 0 && !label.contains("型号") && !label.contains("规格")
                    && (label.contains("名称") || label.contains("品名") || label.contains("产品") || label.contains("商品"))) {
                productIndex = index;
            }
            if (quantityIndex < 0 && label.contains("数量")) quantityIndex = index;
            if (priceIndex < 0 && label.contains("单价")) priceIndex = index;
        }
        Amounts total = ZERO;
        for (int index = 0; index < rows.size(); index++) {
            if (!(rows.get(index) instanceof ArrayNode row)) {
                throw new BusinessException("合同商品明细格式错误");
            }
            String product = row.path(productIndex).asText("").trim();
            if (product.isEmpty() && !hasPricingInput(columns, row)) {
                continue;
            }
            if (product.isEmpty() || quantityIndex < 0 || priceIndex < 0) {
                throw new BusinessException("价税商品需要填写商品名称、数量和单价");
            }
            BigDecimal quantity = decimal(row.path(quantityIndex), "商品数量");
            BigDecimal price = decimal(row.path(priceIndex), "商品报价单价");
            Amounts amounts = calculateLine(lines, pendingLines.remove("PRODUCT:" + index), "PRODUCT", index,
                    basis, quantity, price);
            for (int column = 0; column < columns.size(); column++) {
                String label = columns.path(column).asText("");
                BigDecimal amount = amountForColumn(label, basis, amounts);
                if (amount != null) {
                    while (row.size() <= column) row.add("");
                    row.set(column, TextNode.valueOf(amount.toPlainString()));
                }
            }
            total = add(total, amounts);
        }
        updateSectionSummary(section, total);
        return total;
    }

    private static boolean hasPricingInput(ArrayNode columns, ArrayNode row) {
        for (int index = 0; index < columns.size(); index++) {
            String label = columns.path(index).asText();
            if (!label.contains("数量") && !label.contains("单价") && !label.contains("金额")) continue;
            String value = row.path(index).asText("").trim();
            if (!value.isEmpty() && (value.length() > 40 || !UNSIGNED_DECIMAL.matcher(value).matches()
                    || new BigDecimal(value).signum() != 0)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal amountForColumn(String label, Basis basis, Amounts amounts) {
        if (label.contains("税额")) return amounts.taxAmount();
        if (!label.contains("金额") && !label.contains("价税合计")) return null;
        if (label.contains("不含税") || label.contains("未税")) return amounts.netAmount();
        if (label.contains("含税") || label.contains("价税")) return amounts.grossAmount();
        return basis == Basis.GROSS ? amounts.grossAmount() : amounts.netAmount();
    }

    private static Amounts calculateLine(ArrayNode lines, JsonNode requested, String type, int index,
                                          Basis basis, BigDecimal quantity, BigDecimal unitPrice) {
        if (requested == null) {
            throw new BusinessException("请补充每行商品和费用的税务处理");
        }
        assertSameDecimal(requested, "quantity", quantity, "数量");
        assertSameDecimal(requested, "quoteUnitPrice", unitPrice, "报价单价");
        Treatment treatment;
        try {
            treatment = Treatment.valueOf(requested.path("taxTreatment").asText());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("请选择有效的税务处理");
        }
        BigDecimal rate = decimal(requested.path("taxRate"), "税率或征收率");
        Amounts amounts;
        try {
            amounts = TradeTaxPricing.calculate(basis, treatment, rate, quantity, unitPrice);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(invalid.getMessage());
        }
        ObjectNode line = lines.addObject();
        line.put("lineId", type + ":" + index);
        line.put("lineType", type);
        line.put("rowIndex", index);
        line.put("taxTreatment", treatment.name());
        line.put("taxRate", rate.stripTrailingZeros().toPlainString());
        line.put("quantity", quantity.stripTrailingZeros().toPlainString());
        line.put("quoteUnitPrice", unitPrice.stripTrailingZeros().toPlainString());
        putAmounts(line, amounts);
        return amounts;
    }

    private static void assertSameDecimal(JsonNode requested, String key, BigDecimal value, String label) {
        if (requested.has(key) && decimal(requested.path(key), label).compareTo(value) != 0) {
            throw new BusinessException("合同价税明细中的" + label + "与合同表格不一致");
        }
    }

    private static Basis readBasis(String value) {
        try {
            return Basis.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("请选择有效的报价方式");
        }
    }

    private static BigDecimal decimal(JsonNode node, String label) {
        String text = node.asText("").trim();
        if (text.length() > 40 || !UNSIGNED_DECIMAL.matcher(text).matches()) {
            throw new BusinessException(label + "格式错误");
        }
        return new BigDecimal(text);
    }

    private static Amounts add(Amounts total, Amounts line) {
        BigDecimal gross = total.grossAmount().add(line.grossAmount());
        if (gross.compareTo(MAX_TOTAL) > 0) throw new BusinessException("合同价税合计过大");
        return new Amounts(total.netAmount().add(line.netAmount()), total.taxAmount().add(line.taxAmount()), gross);
    }

    private static void putAmounts(ObjectNode target, Amounts amounts) {
        target.put("netAmount", amounts.netAmount().toPlainString());
        target.put("taxAmount", amounts.taxAmount().toPlainString());
        target.put("grossAmount", amounts.grossAmount().toPlainString());
    }

    private static void updateSectionSummary(ObjectNode section, Amounts amounts) {
        ObjectNode summary = section.path("summary") instanceof ObjectNode present ? present : section.putObject("summary");
        putAmounts(summary, amounts);
        summary.put("totalAmount", amounts.grossAmount().toPlainString());
        summary.put("totalAmountCn", ContractAmountText.uppercase(amounts.grossAmount()));
    }
}
