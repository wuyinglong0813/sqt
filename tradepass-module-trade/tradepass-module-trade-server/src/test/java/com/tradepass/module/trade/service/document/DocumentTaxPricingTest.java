package com.tradepass.module.trade.service.document;

import com.tradepass.framework.common.money.TradeTaxPricing.Amounts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTaxPricingTest {
    @Test
    void finalPartialReturnUsesExactRemainingTaxAndGrossCents() {
        BigDecimal originalQuantity = new BigDecimal("3");
        Amounts original = new Amounts(new BigDecimal("0.03"), new BigDecimal("0.01"), new BigDecimal("0.04"));
        Amounts first = DocumentTaxPricing.refund(original, BigDecimal.ONE, originalQuantity);
        Amounts remaining = DocumentTaxPricing.minus(original, first);
        Amounts finalReturn = DocumentTaxPricing.refund(remaining, new BigDecimal("2"), new BigDecimal("2"));

        assertThat(first.grossAmount()).isEqualByComparingTo("0.01");
        assertThat(finalReturn.taxAmount()).isEqualByComparingTo("0.01");
        assertThat(DocumentTaxPricing.plus(first, finalReturn)).isEqualTo(original);
    }
}
