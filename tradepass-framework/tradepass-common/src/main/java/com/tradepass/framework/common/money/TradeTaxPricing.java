package com.tradepass.framework.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calculates RMB document amounts from one quoted price and a percentage tax rate. */
public final class TradeTaxPricing {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");
    private static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("999999999999.999999");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999999999.9999");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private TradeTaxPricing() {
    }

    public enum Basis {
        GROSS, NET
    }

    public enum Treatment {
        TAXABLE, SIMPLE, EXEMPT, ZERO_RATE, OUT_OF_SCOPE
    }

    public record Amounts(BigDecimal netAmount, BigDecimal taxAmount, BigDecimal grossAmount) {
    }

    public static Amounts calculate(Basis basis, Treatment treatment, BigDecimal taxRate,
                                    BigDecimal quantity, BigDecimal quoteUnitPrice) {
        if (basis == null || treatment == null) {
            throw new IllegalArgumentException("请选择报价方式和税务处理");
        }
        requireDecimal(taxRate, 4, HUNDRED, "税率或征收率无效");
        requireDecimal(quantity, 4, MAX_QUANTITY, "数量无效，最多四位小数");
        requireDecimal(quoteUnitPrice, 6, MAX_UNIT_PRICE, "报价单价无效，最多六位小数");
        if (quantity.signum() == 0) {
            throw new IllegalArgumentException("商品数量必须大于 0");
        }
        boolean taxable = treatment == Treatment.TAXABLE || treatment == Treatment.SIMPLE;
        if (taxable != (taxRate.signum() > 0)) {
            throw new IllegalArgumentException(taxable ? "请输入大于 0 的税率或征收率" : "此税务处理的税率须为 0");
        }
        BigDecimal rate = taxRate.movePointLeft(2);
        BigDecimal quotedAmount = quantity.multiply(quoteUnitPrice).setScale(2, ROUNDING);
        BigDecimal netAmount;
        BigDecimal grossAmount;
        if (basis == Basis.GROSS) {
            grossAmount = quotedAmount;
            netAmount = grossAmount.divide(BigDecimal.ONE.add(rate), 2, ROUNDING);
        } else {
            netAmount = quotedAmount;
            grossAmount = netAmount.add(netAmount.multiply(rate).setScale(2, ROUNDING));
        }
        requireDecimal(grossAmount, 2, MAX_AMOUNT, "价税合计过大");
        return new Amounts(netAmount, grossAmount.subtract(netAmount), grossAmount);
    }

    public static BigDecimal acquisitionCost(BigDecimal grossAmount, BigDecimal taxAmount,
                                             BigDecimal deductibleAmount) {
        requireDecimal(grossAmount, 2, MAX_AMOUNT, "采购价税合计无效");
        requireDecimal(taxAmount, 2, grossAmount, "采购税额无效");
        requireDecimal(deductibleAmount, 2, taxAmount, "可抵扣进项税额须在 0 和采购税额之间");
        return grossAmount.subtract(deductibleAmount).setScale(2, ROUNDING);
    }

    private static void requireDecimal(BigDecimal value, int scale, BigDecimal max, String message) {
        if (value == null || value.signum() < 0 || value.compareTo(max) > 0
                || value.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException(message);
        }
    }
}
