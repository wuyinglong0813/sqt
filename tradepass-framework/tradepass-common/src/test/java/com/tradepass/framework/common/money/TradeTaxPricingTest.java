package com.tradepass.framework.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.tradepass.framework.common.money.TradeTaxPricing.Basis.GROSS;
import static com.tradepass.framework.common.money.TradeTaxPricing.Basis.NET;
import static com.tradepass.framework.common.money.TradeTaxPricing.Treatment.EXEMPT;
import static com.tradepass.framework.common.money.TradeTaxPricing.Treatment.TAXABLE;
import static com.tradepass.framework.common.money.TradeTaxPricing.acquisitionCost;
import static com.tradepass.framework.common.money.TradeTaxPricing.calculate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TradeTaxPricingTest {
    @Test
    void splitsGrossAndNetQuotes() {
        assertThat(calculate(GROSS, TAXABLE, amount("13"), amount("100"), amount("113")))
                .isEqualTo(new TradeTaxPricing.Amounts(amount("10000.00"), amount("1300.00"), amount("11300.00")));
        assertThat(calculate(NET, TAXABLE, amount("13"), amount("100"), amount("120")))
                .isEqualTo(new TradeTaxPricing.Amounts(amount("12000.00"), amount("1560.00"), amount("13560.00")));
    }

    @Test
    void roundsLineBeforeSeparatingOrApplyingTax() {
        assertThat(calculate(GROSS, TAXABLE, amount("13"), amount("0.5000"), amount("0.09")))
                .isEqualTo(new TradeTaxPricing.Amounts(amount("0.04"), amount("0.01"), amount("0.05")));
        assertThat(calculate(NET, TAXABLE, amount("13"), amount("0.5000"), amount("0.09")))
                .isEqualTo(new TradeTaxPricing.Amounts(amount("0.05"), amount("0.01"), amount("0.06")));
    }

    @Test
    void preservesAnExplicitExemption() {
        assertThat(calculate(NET, EXEMPT, BigDecimal.ZERO, amount("2"), amount("20")))
                .isEqualTo(new TradeTaxPricing.Amounts(amount("40.00"), amount("0.00"), amount("40.00")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculate(NET, EXEMPT, amount("13"), amount("2"), amount("20")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> calculate(NET, TAXABLE, BigDecimal.ZERO, amount("2"), amount("20")));
    }

    @Test
    void reducesAcquisitionCostOnlyByTheConfirmedDeduction() {
        assertThat(acquisitionCost(amount("11300.00"), amount("1300.00"), amount("650.00")))
                .isEqualByComparingTo("10650.00");
        assertThat(acquisitionCost(amount("11300.00"), amount("1300.00"), BigDecimal.ZERO))
                .isEqualByComparingTo("11300.00");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> acquisitionCost(amount("11300.00"), amount("1300.00"), amount("1300.01")));
    }

    private BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
