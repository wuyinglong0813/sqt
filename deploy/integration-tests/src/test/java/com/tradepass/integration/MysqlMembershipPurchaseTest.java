package com.tradepass.integration;

import com.fasterxml.jackson.databind.*;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.contract.service.membership.*;
import com.tradepass.module.contract.service.payment.*;
import com.tradepass.module.identity.api.company.CompanyReader;
import com.tradepass.module.identity.api.company.dto.CompanyRespDTO;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.user.UserIdentityOperations;
import com.tradepass.support.RepoRoot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="tradepass.test.mysql.url",matches=".+")
class MysqlMembershipPurchaseTest {
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static String merchantPem,wechatPem,wechatPublic;
    private static final String APP="wx1234567890abcdef",MCH="1234567890",KEY="0123456789abcdef0123456789abcdef";
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"),ZoneOffset.UTC);
    private static final ObjectMapper JSON=new ObjectMapper();
    private MembershipPolicyStore policies;
    private WechatPayConfigStore settings;
    private WechatPayGateway gateway;
    private MembershipPurchaseService service;
    private CompanyReader companies;
    private AccessControlOperations access;
    private UserIdentityOperations users;
    private final Map<String,String> queryStates=new ConcurrentHashMap<>();
    private volatile int lastPrepayAmount;
    private static final String PRODUCTS="""
                tradepass:
                  membership:
                    billing: {enabled: true, order-expire-minutes: 15}
                    products:
                      - {id: vip, name: 企业VIP, type: VIP, price-fen: 99900, first-price-fen: 69900, vip-days: 365, sign-quota: 100, quota-days: 365, on-sale: true}
                      - {id: quota, name: 电子签10份, type: QUOTA, price-fen: 9900, sign-quota: 10, quota-days: 365, on-sale: true}
                      - {id: addon, name: VIP增购包, type: QUOTA, price-fen: 59900, sign-quota: 100, quota-days: 365, vip-only: true, on-sale: true}
                """;

    @BeforeAll static void database() throws Exception {
        String url=System.getProperty("tradepass.test.mysql.url");
        if (!url.matches("jdbc:mysql://[^/]+/tradepass_fix_validation_[a-zA-Z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Only isolated test databases are allowed");
        var source=new DriverManagerDataSource(url,System.getProperty("tradepass.test.mysql.username","root"),
                System.getProperty("tradepass.test.mysql.password",""));
        jdbc=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).locations("filesystem:"+RepoRoot.find().resolve("sql/mysql")).load().migrate();
        var rsa=KeyPairGenerator.getInstance("RSA"); rsa.initialize(2048);
        var merchant=rsa.generateKeyPair(); var wechat=rsa.generateKeyPair();
        merchantPem=pem("PRIVATE KEY",merchant.getPrivate().getEncoded());
        wechatPem=pem("PRIVATE KEY",wechat.getPrivate().getEncoded()); wechatPublic=pem("PUBLIC KEY",wechat.getPublic().getEncoded());
    }
    private static String pem(String type,byte[] bytes) {
        return "-----BEGIN "+type+"-----\n"+Base64.getEncoder().encodeToString(bytes)+"\n-----END "+type+"-----";
    }
    @BeforeEach void setup() throws Exception {
        for(String table:List.of("membership_payment_event","membership_purchase_order","membership_paid_vip",
                "membership_signing_usage","membership_quota_pool","membership_trial_budget","membership_policy_history"))
            jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE membership_policy_state SET revision=0,content_hash='',content='' WHERE id=1");
        AuthContext.set(7,3L);
        policies=new MembershipPolicyStore(jdbc,manager); policies.apply(PRODUCTS,"TEST");
        settings=new WechatPayConfigStore(); settings.apply(paymentConfig());
        companies=mock(CompanyReader.class); access=mock(AccessControlOperations.class); users=mock(UserIdentityOperations.class);
        when(companies.selectById(any())).thenAnswer(inv->company(((Number)inv.getArgument(0)).longValue()));
        when(access.isActiveMember(anyLong(),anyLong())).thenReturn(true);
        when(access.hasPermission(anyLong(),anyString())).thenReturn(true);
        when(users.currentPaymentIdentity()).thenReturn(new UserIdentityOperations.PaymentIdentity(APP,"openid-7"));
        gateway=new WechatPayGateway(settings,this::transport,CLOCK);
        service=new MembershipPurchaseService(jdbc,policies,gateway,companies,access,users,manager,CLOCK);
        queryStates.clear();
    }
    @AfterEach void clear() { AuthContext.clear(); }
    private CompanyRespDTO company(long id) {
        var c=new CompanyRespDTO(); c.setId(id); c.setName("企业"+id); c.setCreditCode("CREDIT-"+id);
        c.setCertificationStatus("VERIFIED"); return c;
    }
    private String paymentConfig() throws Exception {
        var config=new LinkedHashMap<String,Object>();
        config.put("enabled",true); config.put("channel","WECHAT_JSAPI"); config.put("app-id",APP); config.put("mch-id",MCH);
        config.put("merchant-serial","AAAAAAAAAAAAAAAA"); config.put("merchant-private-key-pem",merchantPem); config.put("api-v3-key",KEY);
        config.put("verification-key-id","PUB_KEY_ID_123456"); config.put("verification-key-pem",wechatPublic);
        config.put("notify-url","https://example.test/api/membership/payment/notify"); config.put("allowed-platforms",List.of("android"));
        return JSON.writeValueAsString(Map.of("tradepass",Map.of("payment",config)));
    }
    private MembershipPurchaseService.OrderView create(String product,String key) { return service.create(product,key,"android"); }
    private Map<String,Object> transaction(String no,String state) {
        int amount=jdbc.queryForObject("SELECT amount_fen FROM membership_purchase_order WHERE order_no=?",Integer.class,no);
        var value=new LinkedHashMap<String,Object>();
        value.put("out_trade_no",no); value.put("appid",APP); value.put("mchid",MCH); value.put("trade_state",state);
        value.put("trade_type","JSAPI"); value.put("payer",Map.of("openid","openid-7")); value.put("amount",Map.of("total",amount,"currency","CNY"));
        if ("SUCCESS".equals(state)) { value.put("transaction_id","tx-"+no); value.put("success_time","2026-10-10T16:00:00+08:00"); }
        return value;
    }
    private WechatPayGateway.Reply transport(String method,String path,String body,String authorization) throws Exception {
        String response; int status=200;
        if (path.equals("/v3/pay/transactions/jsapi")) {
            var input=JSON.readTree(body); lastPrepayAmount=input.path("amount").path("total").asInt();
            response=JSON.writeValueAsString(Map.of("prepay_id","prepay-"+input.path("out_trade_no").asText()));
        } else if (path.endsWith("/close")) { response=""; status=204; }
        else {
            String no=path.split("/out-trade-no/")[1].split("\\?")[0], state=queryStates.getOrDefault(no,"NOTPAY");
            if ("NOT_FOUND".equals(state)) { response="{\"code\":\"ORDER_NOT_EXIST\"}"; status=404; }
            else response=JSON.writeValueAsString(transaction(no,state));
        }
        return new WechatPayGateway.Reply(status,headers(response),response);
    }
    private Map<String,String> headers(String body) {
        String time=Long.toString(CLOCK.instant().getEpochSecond());
        return Map.of("Wechatpay-Serial","PUB_KEY_ID_123456","Wechatpay-Timestamp",time,"Wechatpay-Nonce","nonce",
                "Wechatpay-Signature",WechatPayCrypto.sign(time+"\nnonce\n"+body+"\n",wechatPem));
    }
    private String notification(String event,Map<String,Object> payment) throws Exception {
        String nonce="0123456789ab",associated="transaction",plain=JSON.writeValueAsString(payment);
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"AES"),
                new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
        return JSON.writeValueAsString(Map.of("id",event,"event_type","TRANSACTION.SUCCESS","resource",
                Map.of("algorithm","AEAD_AES_256_GCM","nonce",nonce,"associated_data",associated,
                        "ciphertext",Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8))))));
    }
    private void paid(String no,String event) throws Exception {
        String body=notification(event,transaction(no,"SUCCESS")); service.notifyPayment(headers(body),body);
    }
    @Test void disabledCredentialsAndUnsupportedClientsCannotCreateOrders() {
        assertThat(service.catalog("ios").products()).isEmpty();
        assertThatThrownBy(()->service.create("vip","request-01","ios")).hasMessageContaining("客户端");
        settings.apply(null);
        assertThat(service.catalog("android").purchaseEnabled()).isFalse();
        assertThatThrownBy(()->create("vip","request-01")).hasMessageContaining("未开放");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_purchase_order",Long.class)).isZero();
    }
    @Test void orderCreationIsIdempotentFirstOfferIsReservedAndPricesAreSnapshotted() {
        var order=create("vip","request-01");
        assertThat(order.amountFen()).isEqualTo(69900); assertThat(order.firstOffer()).isTrue();
        assertThat(create("vip","request-01").orderNo()).isEqualTo(order.orderNo());
        assertThatThrownBy(()->create("vip","request-02")).hasMessageContaining("待支付");
        policies.apply(PRODUCTS.replace("69900","79900").replace("99900","109900"),"TEST");
        var checkout=service.prepay(order.orderNo(),"android");
        assertThat(checkout.order().amountFen()).isEqualTo(69900); assertThat(lastPrepayAmount).isEqualTo(69900);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_paid_vip",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_quota_pool",Long.class)).isZero();
    }
    @Test void paymentGrantsOnceAndRenewalUsesStandardPriceWithoutErasingExistingCredits() throws Exception {
        var first=create("vip","request-01"); paid(first.orderNo(),"event-1"); paid(first.orderNo(),"event-1");
        var firstView=service.detail(first.orderNo(),"android");
        assertThat(firstView.status()).isEqualTo("PAID"); assertThat(firstView.vipEndAt()).isEqualTo("2027-10-10 16:00:00");
        assertThat(jdbc.queryForObject("SELECT SUM(total_amount) FROM membership_quota_pool",Long.class)).isEqualTo(100);
        var renewal=create("vip","request-02"); assertThat(renewal.firstOffer()).isFalse(); assertThat(renewal.amountFen()).isEqualTo(99900);
        paid(renewal.orderNo(),"event-2");
        assertThat(service.detail(renewal.orderNo(),"android").vipStartAt()).isEqualTo(firstView.vipEndAt());
        assertThat(jdbc.queryForObject("SELECT SUM(total_amount) FROM membership_quota_pool",Long.class)).isEqualTo(200);
        assertThat(service.catalog("android").products().stream().filter(p->p.id().equals("addon")).findFirst().orElseThrow().canBuy()).isTrue();
    }
    @Test void forgedOrMismatchedPaymentsNeverGrantRights() throws Exception {
        var order=create("vip","request-01");
        for(String field:List.of("amount","payer","appid","mchid","trade_type")) {
            var payment=transaction(order.orderNo(),"SUCCESS");
            payment.put(field,switch(field) {
                case "amount" -> Map.of("total",1,"currency","CNY");
                case "payer" -> Map.of("openid","other");
                case "trade_type" -> "NATIVE";
                default -> "wrong";
            });
            String body=notification("bad-"+field,payment);
            assertThatThrownBy(()->service.notifyPayment(headers(body),body)).isInstanceOf(IllegalArgumentException.class);
        }
        String body=notification("fake",transaction(order.orderNo(),"SUCCESS"));
        var fake=new HashMap<>(headers(body)); fake.put("Wechatpay-Signature","invalid");
        assertThatThrownBy(()->service.notifyPayment(fake,body)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_paid_vip",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_payment_event",Long.class)).isZero();
    }
    @Test void closingBillingOrAddingBlacklistDoesNotLoseAlreadyPaidRights() throws Exception {
        var order=create("vip","request-01"); service.prepay(order.orderNo(),"android");
        policies.apply(PRODUCTS.replace("enabled: true","enabled: false")
                .replace("    products:","    blacklist: [{company-id: '3'}]\n    products:"),"TEST");
        settings.apply("tradepass: {payment: {enabled: false}}");
        paid(order.orderNo(),"event-1");
        assertThat(service.detail(order.orderNo(),"android").status()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_paid_vip",Long.class)).isEqualTo(1);
    }
    @Test void otherCompaniesAndNonManagersCannotReadOrBuyOrders() {
        var order=create("quota","request-01");
        AuthContext.set(7,4L);
        assertThatThrownBy(()->service.detail(order.orderNo(),"android")).hasMessageContaining("当前企业");
        AuthContext.set(7,3L);
        doThrow(new com.tradepass.framework.common.exception.BusinessException("无权购买")).when(access).requireManager(3L);
        assertThatThrownBy(()->create("quota","request-02")).hasMessageContaining("无权");
        assertThatThrownBy(()->service.orders("android")).hasMessageContaining("无权");
    }
    @Test void queryCompensationAndDuplicateCallbacksShareOneGrantTransaction() throws Exception {
        var order=create("quota","request-01"); service.prepay(order.orderNo(),"android");
        queryStates.put(order.orderNo(),"SUCCESS");
        AuthContext.clear(); service.reconcilePending(); // Works without a frontend or logged-in user.
        paid(order.orderNo(),"event-1");
        AuthContext.set(7,3L);
        assertThat(service.detail(order.orderNo(),"android").status()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT SUM(total_amount) FROM membership_quota_pool",Long.class)).isEqualTo(10);
    }
    @Test void parallelNotificationsGrantQuotaOnlyOnce() throws Exception {
        var order=create("quota","request-01");
        String body=notification("event-1",transaction(order.orderNo(),"SUCCESS"));
        var signature=headers(body); var executor=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) futures.add(executor.submit(()->service.notifyPayment(signature,body)));
            for(var f:futures) f.get(10,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_quota_pool",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT SUM(total_amount) FROM membership_quota_pool",Long.class)).isEqualTo(10);
        } finally { executor.shutdownNow(); }
    }
    @Test void unstartedCancelledOrdersCloseWithoutContactingPaymentProvider() {
        var order=create("vip","request-01");
        assertThat(service.close(order.orderNo(),"android").status()).isEqualTo("CLOSED");
        assertThat(create("vip","request-02").firstOffer()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_quota_pool",Long.class)).isZero();
    }
}
