package com.tradepass.module.trade.service.retail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.money.TradeTaxPricing;
import com.tradepass.framework.common.money.TradeTaxPricing.Amounts;
import com.tradepass.framework.common.money.TradeTaxPricing.Basis;
import com.tradepass.framework.common.money.TradeTaxPricing.Treatment;
import com.tradepass.module.trade.service.document.DocumentTaxPricing.Line;
import com.tradepass.module.trade.service.document.DocumentTaxPricing.Snapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class RetailTaxPricing {
    private static final Pattern UNSIGNED_DECIMAL = Pattern.compile("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");

    private RetailTaxPricing() {
    }

    record Request(Basis basis, Map<Integer, JsonNode> lines) {
        Line price(int index, String type, BigDecimal quantity, BigDecimal unitPrice) {
            JsonNode input = lines.get(index);
            if (!type.equals(input.path("lineType").asText())) {
                throw new BusinessException("采购价税明细与商品明细不一致");
            }
            assertSameDecimal(input, "quantity", quantity, "数量");
            assertSameDecimal(input, "quoteUnitPrice", unitPrice, "报价单价");
            Treatment treatment;
            try {
                treatment = Treatment.valueOf(input.path("taxTreatment").asText());
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException("请选择有效的税务处理");
            }
            BigDecimal rate = decimal(input.path("taxRate"), "税率或征收率");
            try {
                return new Line(index, type, type + ":" + index, null, treatment, rate, quantity, unitPrice,
                        TradeTaxPricing.calculate(basis, treatment, rate, quantity, unitPrice));
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException(invalid.getMessage());
            }
        }
    }

    record PurchaseCost(int rowIndex, String status, BigDecimal deductibleAmount, BigDecimal costAmount) {
        Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("rowIndex", rowIndex);
            view.put("status", status);
            view.put("deductibleAmount", deductibleAmount == null ? null : deductibleAmount.toPlainString());
            view.put("costAmount", costAmount.toPlainString());
            return view;
        }
    }

    static Request request(ObjectMapper mapper, Object input, int lineCount) {
        if (input == null) return null;
        JsonNode data = node(mapper, input, "采购价税明细格式错误");
        JsonNode lines = data.path("lines");
        JsonNode version = data.path("version");
        if (!data.isObject() || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1
                || !lines.isArray() || lines.size() != lineCount) {
            throw new BusinessException("请填写每条采购明细的税务处理");
        }
        Basis basis;
        try {
            basis = Basis.valueOf(data.path("basis").asText());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("请选择有效的报价方式");
        }
        Map<Integer, JsonNode> indexed = new LinkedHashMap<>();
        for (JsonNode line : lines) {
            int index = rowIndex(line);
            if (index >= lineCount || indexed.putIfAbsent(index, line) != null) {
                throw new BusinessException("采购价税明细行号不正确");
            }
        }
        return new Request(basis, indexed);
    }

    static Map<Integer, PurchaseCost> purchaseCosts(ObjectMapper mapper, Snapshot pricing, Object input) {
        Map<Integer, Line> products = new LinkedHashMap<>();
        for (Line line : pricing.lines()) {
            if ("PRODUCT".equals(line.lineType())) products.put(line.rowIndex(), line);
        }
        Map<Integer, PurchaseCost> result = new LinkedHashMap<>();
        if (input != null) {
            JsonNode options = node(mapper, input, "进项税确认数据格式不正确");
            if (!options.isArray() || options.size() > products.size()) {
                throw new BusinessException("进项税确认数据格式不正确");
            }
            for (JsonNode option : options) {
                int index = rowIndex(option);
                Line line = products.get(index);
                if (line == null || result.containsKey(index)) {
                    throw new BusinessException("进项税确认商品行无效");
                }
                String status = option.path("status").asText();
                BigDecimal amount = option.hasNonNull("deductibleAmount")
                        ? decimal(option.path("deductibleAmount"), "可抵扣进项税额") : null;
                if ("PENDING".equals(status) && (amount == null || amount.signum() == 0)) {
                    result.put(index, pending(line));
                } else if ("CONFIRMED".equals(status) && amount != null) {
                    try {
                        result.put(index, new PurchaseCost(index, status, amount,
                                TradeTaxPricing.acquisitionCost(line.amounts().grossAmount(), line.amounts().taxAmount(), amount)));
                    } catch (IllegalArgumentException invalid) {
                        throw new BusinessException(invalid.getMessage());
                    }
                } else {
                    throw new BusinessException("请选择进项税确认状态及可抵扣金额");
                }
            }
        }
        products.forEach((index, line) -> result.putIfAbsent(index, pending(line)));
        return result;
    }

    static Amounts refund(Amounts remaining, BigDecimal quantity, BigDecimal availableQuantity) {
        if (quantity.compareTo(availableQuantity) == 0) return remaining;
        BigDecimal gross = remaining.grossAmount().multiply(quantity).divide(availableQuantity, 2, RoundingMode.HALF_UP);
        BigDecimal tax = remaining.taxAmount().multiply(quantity).divide(availableQuantity, 2, RoundingMode.HALF_UP);
        tax = tax.max(gross.subtract(remaining.netAmount())).min(remaining.taxAmount()).min(gross);
        return new Amounts(gross.subtract(tax), tax, gross);
    }

    private static PurchaseCost pending(Line line) {
        return line.amounts().taxAmount().signum() == 0
                ? new PurchaseCost(line.rowIndex(), "CONFIRMED", BigDecimal.ZERO, line.amounts().grossAmount())
                : new PurchaseCost(line.rowIndex(), "PENDING", null, line.amounts().grossAmount());
    }

    private static int rowIndex(JsonNode line) {
        JsonNode index = line.path("rowIndex");
        if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0) {
            throw new BusinessException("价税明细行号不正确");
        }
        return index.intValue();
    }

    private static JsonNode node(ObjectMapper mapper, Object value, String error) {
        try {
            JsonNode result = value instanceof String content ? mapper.readTree(content) : mapper.valueToTree(value);
            if (result != null) return result;
        } catch (Exception ignored) {
            // Return a business validation error for malformed client data.
        }
        throw new BusinessException(error);
    }

    private static void assertSameDecimal(JsonNode row, String key, BigDecimal value, String label) {
        if (row.has(key) && decimal(row.path(key), label).compareTo(value) != 0) {
            throw new BusinessException("采购价税明细中的" + label + "与商品明细不一致");
        }
    }

    private static BigDecimal decimal(JsonNode field, String label) {
        String value = field.asText("").trim();
        if (value.length() > 40 || !UNSIGNED_DECIMAL.matcher(value).matches()) {
            throw new BusinessException(label + "格式不正确");
        }
        return new BigDecimal(value);
    }
}
