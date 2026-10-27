package com.tradepass.framework.fadada.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FadadaPropertiesTest {
    @Test
    void legalRepresentativeOnlyOnboardingIsOptInAndBindsTheDeploymentSetting() {
        assertThat(new FadadaProperties().isLegalRepresentativeOnlyOnboardingEnabled()).isFalse();
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("tradepass.fadada.legal-representative-only-onboarding-enabled", "true"));
        var properties = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("tradepass.fadada", FadadaProperties.class).get();
        assertThat(properties.isLegalRepresentativeOnlyOnboardingEnabled()).isTrue();
        assertThat(properties.isOperatorOpenUserIdConfirmed()).isFalse();
    }

    @Test
    void platformInitiationIsOptInAndReturnsTheBusinessInitiatorWhenDisabled() {
        var properties = new FadadaProperties();
        assertThat(properties.isPlatformInitiatedSigningEnabled()).isFalse();
        assertThat(properties.signingInitiatorOpenCorpId("corp-business")).isEqualTo("corp-business");
        assertThat(configured().signingInitiatorOpenCorpId("corp-business")).isEqualTo("corp-business");
    }

    @Test
    void platformInitiationUsesOnlyTheOperatorVerifiedApplicationOwner() {
        var properties = configured(); properties.setPlatformInitiatedSigningEnabled(true);
        assertThat(properties.signingInitiatorOpenCorpId("corp-business")).isEqualTo("corp-owner");
        assertThat(properties.signingInitiatorOpenCorpId(null)).isEqualTo("corp-owner");
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "wrong-app", "openCorp", "clientCorp", "credit", "current-app"})
    void platformInitiationFailsClosedForMissingOrMismatchedOwnerConfiguration(String condition) {
        var properties = configured(); properties.setPlatformInitiatedSigningEnabled(true);
        switch (condition) {
            case "app" -> properties.setOwnerEnterpriseAppId(null);
            case "wrong-app" -> properties.setOwnerEnterpriseAppId("another-app");
            case "openCorp" -> properties.setOwnerEnterpriseOpenCorpId(" ");
            case "clientCorp" -> properties.setOwnerEnterpriseClientCorpId(null);
            case "credit" -> properties.setOwnerEnterpriseCreditCode("");
            case "current-app" -> properties.setAppId("");
        }
        assertThatThrownBy(() -> properties.signingInitiatorOpenCorpId("corp-business"))
                .hasMessageContaining("平台签署配置未就绪");
    }

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
