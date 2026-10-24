package com.tradepass.module.contract.service.membership;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MembershipProviderInitiatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T08:00:00Z"), ZoneOffset.UTC);
    private static final String SHA256 = "a".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-24T16:00:00");

    @AfterEach void clearAuth() { AuthContext.clear(); }

    @Test void newIntentWritesAndReturnsTheProviderInitiator() {
        var f = new Fixture();
        doReturn(null, reservation("RESERVED", "platform-original")).when(f.service).existing(8L, 2);
        var reserved = f.service.reserve(f.company, 8L, 2, "{}", SHA256, "platform-original");
        verify(f.jdbc).update(contains("provider_initiator_open_corp_id"), eq(8L), eq(2), eq(3L), eq("CREDIT-3"),
                eq("SPECIAL"), eq("SPECIAL_UNLIMITED"), eq(7L), eq(12L), eq("{}"), eq(SHA256), eq(NOW), eq("platform-original"));
        assertThat(reserved.createAllowed()).isTrue();
        assertThat(reserved.providerInitiatorOpenCorpId()).isEqualTo("platform-original");
    }

    @Test void compatibilityOverloadCreatesALegacyNullInitiatorIntent() {
        var f = new Fixture();
        doReturn(null, reservation("RESERVED", null)).when(f.service).existing(8L, 2);
        var reserved = f.service.reserve(f.company, 8L, 2, "{}", SHA256);
        verify(f.jdbc).update(contains("provider_initiator_open_corp_id"), eq(8L), eq(2), eq(3L), eq("CREDIT-3"),
                eq("SPECIAL"), eq("SPECIAL_UNLIMITED"), eq(7L), eq(12L), eq("{}"), eq(SHA256), eq(NOW), isNull());
        assertThat(reserved.providerInitiatorOpenCorpId()).isNull();
        assertThat(new MembershipService.Reservation(1L, "RESERVED", false, null, null, null, "{}", SHA256)
                .providerInitiatorOpenCorpId()).isNull();
    }

    @Test void releasedIntentCanFreezeADifferentInitiator() {
        var f = new Fixture();
        doReturn(reservation("RELEASED", "platform-original"), reservation("RESERVED", "platform-new"))
                .when(f.service).existing(8L, 2);
        var reserved = f.service.reserve(f.company, 8L, 2, "{}", SHA256, "platform-new");
        verify(f.jdbc).update(contains("provider_initiator_open_corp_id=?"), eq("SPECIAL"), eq("SPECIAL_UNLIMITED"),
                eq(7L), eq(12L), eq("{}"), eq(SHA256), eq(NOW), eq("platform-new"), eq(8L), eq(2));
        assertThat(reserved.createAllowed()).isTrue();
        assertThat(reserved.providerInitiatorOpenCorpId()).isEqualTo("platform-new");
    }

    @ParameterizedTest @ValueSource(strings = {"RESERVED", "UNCERTAIN"})
    void pendingCreationCannotChangeItsFrozenInitiator(String state) {
        var f = new Fixture();
        doReturn(reservation(state, "platform-original")).when(f.service).existing(8L, 2);
        assertThatThrownBy(() -> f.service.reserve(f.company, 8L, 2, "{}", SHA256, "platform-new"))
                .hasMessageContaining("待核实");
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void consumedCreationReturnsItsOriginalInitiatorWithoutReservingAgain() {
        var f = new Fixture();
        var original = reservation("CONSUMED", "platform-original");
        doReturn(original).when(f.service).existing(8L, 2);
        assertThat(f.service.reserve(f.company, 8L, 2, "{}", SHA256, "platform-new")).isSameAs(original);
        verify(f.jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void existingReservationMapsTheFrozenProviderInitiatorFromTheDatabase() throws Exception {
        assertExistingMapping("platform-original");
    }

    @Test void existingLegacyReservationMapsNullWithoutUsingTheCurrentPlatform() throws Exception {
        assertExistingMapping(null);
    }

    private void assertExistingMapping(String providerInitiator) throws Exception {
        var f = new Fixture();
        var rows = mock(ResultSet.class);
        when(rows.getLong("id")).thenReturn(10L);
        when(rows.getString("status")).thenReturn("UNCERTAIN");
        when(rows.getString("provider_initiator_open_corp_id")).thenReturn(providerInitiator);
        when(f.jdbc.query(contains("provider_initiator_open_corp_id"), any(RowMapper.class), eq(8L), eq(2)))
                .thenAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rows, 0)));
        var existing = f.service.existing(8L, 2);
        assertThat(existing.id()).isEqualTo(10L);
        assertThat(existing.providerInitiatorOpenCorpId()).isEqualTo(providerInitiator);
        assertThat(existing.createAllowed()).isFalse();
    }

    private static MembershipService.Reservation reservation(String state, String providerInitiator) {
        return new MembershipService.Reservation(10L, state, false, null, null, null, "{}", SHA256, providerInitiator);
    }

    private static class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final MembershipPolicyStore policies = mock(MembershipPolicyStore.class);
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final CompanyRespDTO company = new CompanyRespDTO();
        final MembershipService service;
        Fixture() {
            company.setId(3L); company.setName("企业3"); company.setCreditCode("CREDIT-3");
            AuthContext.set(12L, 3L);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(policies.current(true)).thenReturn(new MembershipPolicyStore.Snapshot(7L,
                    MembershipPolicy.parse("tradepass: {membership: {whitelist: [{company-id: '3', mode: UNLIMITED}]}}"),
                    "2026-10-24 16:00:00", true));
            service = spy(new MembershipService(jdbc, policies, manager, CLOCK));
        }
    }
}
