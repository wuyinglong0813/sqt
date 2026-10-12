package com.tradepass.module.contract.service.membership;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.contract.service.payment.WechatPayGateway;
import com.tradepass.module.contract.service.payment.WechatPaySettings;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MembershipPurchaseServiceTest {
    private static final long TARGET = 2104508694149730306L, OTHER = 2104508694149730307L, USER = 7L;
    private static final String APP_ID = "wx1234567890abcdef";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MembershipPolicyStore policies = mock(MembershipPolicyStore.class);
    private final WechatPayGateway payments = mock(WechatPayGateway.class);
    private final CompanyReader companies = mock(CompanyReader.class);
    private final AccessControlOperations access = mock(AccessControlOperations.class);
    private final UserIdentityOperations users = mock(UserIdentityOperations.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final Map<String, Object> storedOrder = new HashMap<>();
    private MembershipPurchaseService service;

    @BeforeEach void setup() {
        var policy = MembershipPolicy.parse("""
                tradepass:
                  membership:
                    billing: {enabled: true}
                    products:
                      - id: sign-test-9
                        name: 电子签9份
                        type: QUOTA
                        price-fen: 100
                        sign-quota: 9
                        quota-days: 365
                        on-sale: true
                        allowed-company-ids: ['2104508694149730306']
                      - id: sign-10
                        name: 电子签10份
                        type: QUOTA
                        price-fen: 9900
                        sign-quota: 10
                        on-sale: true
                """);
        when(policies.current(anyBoolean())).thenReturn(new MembershipPolicyStore.Snapshot(1, policy, "", true));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(payments.ready()).thenReturn(true);
        when(payments.platformAllowed("ios")).thenReturn(true);
        when(payments.settings()).thenReturn(new WechatPaySettings(true, APP_ID, "1118613088", "AAAAAAAAAAAAAAAA",
                "merchant-private", "0123456789abcdef0123456789abcdef", "PUB_KEY_ID_test", "wechat-public",
                "https://example.test/api/membership/payment/notify", Set.of("ios")));
        when(users.currentPaymentIdentity()).thenReturn(new UserIdentityOperations.PaymentIdentity(APP_ID, "buyer-openid"));
        when(access.isActiveMember(anyLong(), eq(USER))).thenReturn(true);
        when(access.hasPermission(anyLong(), eq("member_manage"))).thenReturn(true);
        service = new MembershipPurchaseService(jdbc, policies, payments, companies, access, users, transactions,
                Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), ZoneOffset.UTC));
        selectCompany(TARGET);
    }

    @AfterEach void clearContext() { AuthContext.clear(); }

    @Test void selectedEnterpriseSeesOneYuanNineSignatureOfferAlongsidePublicProducts() {
        var catalog = service.catalog("ios");
        assertThat(catalog.purchaseEnabled()).isTrue();
        assertThat(catalog.products()).extracting(MembershipPurchaseService.ProductView::id)
                .containsExactly("sign-test-9", "sign-10");
        var offer = catalog.products().get(0);
        assertThat(offer.amountFen()).isEqualTo(100);
        assertThat(offer.signQuota()).isEqualTo(9);
        assertThat(offer.canBuy()).isTrue();
    }

    @Test void otherEnterprisesKeepPublicPricesAndCannotSeeTheRestrictedOffer() {
        selectCompany(OTHER);
        var catalog = service.catalog("ios");
        assertThat(catalog.purchaseEnabled()).isTrue();
        assertThat(catalog.products()).hasSize(1);
        assertThat(catalog.products().get(0).id()).isEqualTo("sign-10");
        assertThat(catalog.products().get(0).amountFen()).isEqualTo(9900);
    }

    @Test void directRequestsFromAnotherEnterpriseCannotCreateTheDiscountedOrder() {
        selectCompany(OTHER);
        assertThatThrownBy(() -> service.create("sign-test-9", "request-123456", "ios"))
                .isInstanceOf(BusinessException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(payments, never()).prepay(anyString(), anyString(), anyInt(), anyString(), any(), anyString(), anyString());
    }

    @Test void selectedEnterpriseOrderSnapshotsOneYuanAndNineSignaturesBeforePayment() throws Exception {
        useOrderStorage();
        var order = service.create("sign-test-9", "request-123456", "ios");
        assertThat(order.companyId()).isEqualTo(Long.toString(TARGET));
        assertThat(order.amountFen()).isEqualTo(100);
        assertThat(order.signQuota()).isEqualTo(9);
        assertThat(order.quotaDays()).isEqualTo(365);
        assertThat(order.status()).isEqualTo("PENDING");
        assertThat(order.canPay()).isTrue();
        verify(jdbc, never()).update(contains("INSERT INTO membership_quota_pool"), any(Object[].class));
        verify(payments, never()).prepay(anyString(), anyString(), anyInt(), anyString(), any(), anyString(), anyString());
    }

    private void selectCompany(long companyId) {
        var company = new CompanyRespDTO();
        company.setId(companyId);
        company.setName("企业 " + companyId);
        company.setCreditCode("SUBJECT-" + companyId);
        company.setCertificationStatus("VERIFIED");
        when(companies.selectById(companyId)).thenReturn(company);
        AuthContext.set(USER, companyId);
    }

    @SuppressWarnings("unchecked")
    private void useOrderStorage() throws Exception {
        doAnswer(call -> {
            String sql = call.getArgument(0);
            String[] columns = sql.substring(sql.indexOf('(') + 1, sql.indexOf(')')).split(",");
            Object[] arguments = call.getArguments();
            for (int index = 0; index < columns.length; index++) {
                storedOrder.put(columns[index].trim(), arguments[index + 1]);
            }
            storedOrder.put("status", "PENDING");
            return 1;
        }).when(jdbc).update(contains("INSERT INTO membership_purchase_order"), any(Object[].class));
        var row = mock(ResultSet.class);
        when(row.getString(anyString())).thenAnswer(call -> (String) storedOrder.get(call.getArgument(0)));
        when(row.getLong(anyString())).thenAnswer(call -> ((Number) storedOrder.get(call.getArgument(0))).longValue());
        when(row.getInt(anyString())).thenAnswer(call -> ((Number) storedOrder.get(call.getArgument(0))).intValue());
        when(row.getBoolean(anyString())).thenAnswer(call -> Boolean.TRUE.equals(storedOrder.get(call.getArgument(0))));
        when(row.getTimestamp(anyString())).thenAnswer(call -> {
            LocalDateTime value = (LocalDateTime) storedOrder.get(call.getArgument(0));
            return value == null ? null : Timestamp.valueOf(value);
        });
        doAnswer(call -> List.of(((RowMapper<Object>) call.getArgument(1)).mapRow(row, 0)))
                .when(jdbc).query(eq("SELECT * FROM membership_purchase_order WHERE order_no=?"),
                        any(RowMapper.class), any(Object[].class));
    }
}
