package com.tradepass.module.contract.service.membership;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class MembershipPolicyTest {
    @Test void beijingDeadlineIncludesItsEntireLastSecondRegardlessOfHostTimezone() {
        var policy = MembershipPolicy.parse("""
                tradepass:
                  membership:
                    trial:
                      enabled: true
                      activity-id: launch
                      end-at: "2026-11-08 23:59:59"
                """);
        assertThat(policy.trial().active(Instant.parse("2026-11-08T15:59:59.999999Z"))).isTrue();
        assertThat(policy.trial().active(Instant.parse("2026-11-08T16:00:00Z"))).isFalse();
        assertThat(MembershipPolicy.displayEnd(policy.trial().endExclusive())).isEqualTo("2026-11-08 23:59:59");
    }
    @Test void independentEnterpriseDeadlineAndLongIdsArePreserved() {
        var policy = MembershipPolicy.parse("""
                tradepass:
                  membership:
                    whitelist:
                      - company-id: "9007199254740993"
                        mode: QUOTA
                        quota-total: 200
                        valid-until: "2026-12-31 23:59:59"
                      - company-id: "2"
                        mode: UNLIMITED
                        valid-until: null
                """);
        assertThat(policy.whitelist().get(9007199254740993L).quotaTotal()).isEqualTo(200);
        assertThat(policy.whitelist().get(2L).active(Instant.parse("2030-01-01T00:00:00Z"))).isTrue();
    }
    @Test void invalidAndAmbiguousPoliciesAreRejectedTogether() {
        for (String fields : new String[] {
                "whitelist: [{company-id: '1', mode: QUOTA}]",
                "whitelist: [{company-id: '1', mode: UNLIMITED, quota-total: 0}]",
                "whitelist: [{company-id: '1', mode: QUOTA, quota-total: -1}]",
                "whitelist: [{company-id: '1', mode: QUOTA, quota-total: 1.5}]",
                "whitelist: [{company-id: '1', mode: UNLIMITED}, {company-id: '1', mode: UNLIMITED}]",
                "trial: {enabled: true, activity-id: launch, end-at: '2026-02-30 00:00:00'}",
                "billing: {enabled: 'true'}",
                "whitelits: []"
        }) assertThatThrownBy(() -> MembershipPolicy.parse("tradepass:\n  membership:\n    " + fields))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MembershipPolicy.parse("tradepass:\n  membership:\n    billing: {}\n    billing: {}\n"))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void missingOrEmptyConfigurationDoesNotTurnOnFreeSigningOrPayments() {
        for (String content : new String[]{null, "", "tradepass: {membership: {}}"})
            assertThat(MembershipPolicy.parse(content)).isEqualTo(MembershipPolicy.empty());
    }

    @Test void enterpriseOfferPreservesLongCompanyIdAndPublicProductsRemainAvailable() {
        var restricted = MembershipPolicy.parse(product("allowed-company-ids: ['2104508694149730306']"))
                .products().get(0);
        assertThat(restricted.allowedCompanyIds()).containsExactly(2104508694149730306L);
        assertThat(restricted.availableForCompany(2104508694149730306L)).isTrue();
        assertThat(restricted.availableForCompany(2104508694149730307L)).isFalse();
        assertThat(restricted.priceFen()).isEqualTo(100);
        assertThat(restricted.signQuota()).isEqualTo(9);
        assertThat(MembershipPolicy.parse(product("")).products().get(0)
                .availableForCompany(2104508694149730307L)).isTrue();
    }

    @Test void invalidEnterpriseRestrictionsCannotTurnAnOfferIntoAPublicProduct() {
        for (String value : new String[] {"[]", "null", "'2104508694149730306'", "[0]", "[-1]", "[1.5]",
                "[true]", "['9223372036854775808']", "['1', '1']", "['2104508694149730306', null]"}) {
            assertThatThrownBy(() -> MembershipPolicy.parse(product("allowed-company-ids: " + value)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private String product(String restriction) {
        return """
                tradepass:
                  membership:
                    products:
                      - id: sign-test-9
                        name: 电子签9份
                        type: QUOTA
                        price-fen: 100
                        sign-quota: 9
                        quota-days: 365
                        on-sale: true
                """ + "        " + restriction + "\n";
    }
}
