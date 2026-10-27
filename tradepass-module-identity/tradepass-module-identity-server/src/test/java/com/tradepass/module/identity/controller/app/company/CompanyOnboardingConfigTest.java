package com.tradepass.module.identity.controller.app.company;

import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.service.certification.CompanyCertificationService;
import com.tradepass.module.identity.service.company.CompanyService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CompanyOnboardingConfigTest {
    @Test
    void reportsTheServerPolicyAndKeepsTheExistingMockFlag() {
        var properties = new FadadaProperties();
        var controller = new CompanyController(mock(CompanyService.class),
                mock(CompanyCertificationService.class), properties);
        assertThat(controller.caConfig().data()).containsEntry("legalOnlyOnboardingEnabled", false)
                .containsEntry("mockEnabled", false);
        properties.setLegalRepresentativeOnlyOnboardingEnabled(true);
        assertThat(controller.caConfig().data()).containsEntry("legalOnlyOnboardingEnabled", true);
    }
}
