package com.tradepass.module.identity.service.permission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RolePermissionServiceTest {
    private final RolePermissionService service = new RolePermissionServiceImpl();

    @Test
    void returnsBuiltInRolePermissions() {
        assertThat(service.roleText("LEGAL")).isEqualTo("法人");
        assertThat(service.role("ADMIN").permissions())
                .contains("member_manage", "contract_template", "counterparty_view")
                .doesNotContain("all", "contract_sign");
    }

    @Test
    void allAssignableDefaultRolesIncludeCounterpartyViewing() {
        for (String code : java.util.List.of("ADMIN", "SALES", "PURCHASER", "FINANCE")) {
            assertThat(service.role(code).permissions()).as(code).contains("counterparty_view");
        }
        assertThat(service.role("LEGAL").permissions()).contains("all");
        assertThat(service.role("GUEST").permissions()).isEmpty();
        assertThat(service.role("LEGAL_CANDIDATE").permissions()).isEmpty();
    }

    @Test
    void fallsBackForBlankAndCustomRoleCodes() {
        assertThat(service.role(null).text()).isEqualTo("访客");
        assertThat(service.role(" ").permissions()).isEmpty();
        assertThat(service.role("AUDITOR").text()).isEqualTo("AUDITOR");
        assertThat(service.role("AUDITOR").permissions()).isEmpty();
    }
}
