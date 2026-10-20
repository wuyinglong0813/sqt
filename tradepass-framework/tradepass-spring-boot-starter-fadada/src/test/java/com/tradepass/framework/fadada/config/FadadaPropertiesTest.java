package com.tradepass.framework.fadada.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FadadaPropertiesTest {
    @Test
    void defaultConfigurationDoesNotTrustAnyEnterpriseIncludingEmptyInput() {
        var properties = new FadadaProperties();
        assertThat(properties.matchesOwnerEnterprise("corp-owner", "provider-default", "owner-credit")).isFalse();
        assertThat(properties.matchesOwnerEnterprise("", "", "")).isFalse();
        assertThat(properties.matchesOwnerEnterprise(null, null, null)).isFalse();
    }

    @Test
    void theVerifiedOwnerTupleOnlyAppliesToItsExactApplicationAndEnterprise() {
        var properties = configured();
        assertThat(properties.matchesOwnerEnterprise("corp-owner", "provider-default", "owner-credit")).isTrue();
        assertThat(properties.matchesOwnerEnterprise("another-corp", "provider-default", "owner-credit")).isFalse();
        assertThat(properties.matchesOwnerEnterprise("corp-owner", "another-binding", "owner-credit")).isFalse();
        assertThat(properties.matchesOwnerEnterprise("corp-owner", "provider-default", "another-credit")).isFalse();
        assertThat(properties.matchesOwnerEnterprise("corp-owner", "provider-default", "OWNER-CREDIT")).isFalse();
        assertThat(properties.matchesOwnerEnterprise(" corp-owner", "provider-default", "owner-credit")).isFalse();
        properties.setAppId("5270");
        assertThat(properties.matchesOwnerEnterprise("corp-owner", "provider-default", "owner-credit")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "openCorp", "clientCorp", "credit"})
    void anIncompleteOperatorConfigurationNeverEnablesTheException(String omitted) {
        var properties = configured();
        switch (omitted) {
            case "app" -> properties.setOwnerEnterpriseAppId(null);
            case "openCorp" -> properties.setOwnerEnterpriseOpenCorpId(" ");
            case "clientCorp" -> properties.setOwnerEnterpriseClientCorpId("");
            case "credit" -> properties.setOwnerEnterpriseCreditCode(null);
        }
        assertThat(properties.matchesOwnerEnterprise("corp-owner", "provider-default", "owner-credit")).isFalse();
    }

    private FadadaProperties configured() {
        var properties = new FadadaProperties();
        properties.setAppId("00005270");
        properties.setOwnerEnterpriseAppId("00005270");
        properties.setOwnerEnterpriseOpenCorpId("corp-owner");
        properties.setOwnerEnterpriseClientCorpId("provider-default");
        properties.setOwnerEnterpriseCreditCode("owner-credit");
        return properties;
    }
}
