package com.tradepass.module.contract.service.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WechatPayGatewayTest {
    private static String merchantPrivate,merchantPublic,wechatPrivate,wechatPublic;
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"),ZoneOffset.UTC);
    private static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void keys() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var merchant=generator.generateKeyPair(); var wechat=generator.generateKeyPair();
        merchantPrivate=pem("PRIVATE KEY",merchant.getPrivate().getEncoded());
        merchantPublic=pem("PUBLIC KEY",merchant.getPublic().getEncoded());
        wechatPrivate=pem("PRIVATE KEY",wechat.getPrivate().getEncoded());
        wechatPublic=pem("PUBLIC KEY",wechat.getPublic().getEncoded());
    }
    private static String pem(String type,byte[] value) {
        return "-----BEGIN "+type+"-----\n"+Base64.getEncoder().encodeToString(value)+"\n-----END "+type+"-----";
    }
    private String config(boolean enabled) throws Exception {
        var map=new LinkedHashMap<String,Object>();
        map.put("enabled",enabled); map.put("channel","WECHAT_JSAPI"); map.put("app-id","wx1234567890abcdef");
        map.put("mch-id","1234567890"); map.put("merchant-serial","AAAAAAAAAAAAAAAA");
        map.put("merchant-private-key-pem",merchantPrivate); map.put("api-v3-key","0123456789abcdef0123456789abcdef");
        map.put("verification-key-id","PUB_KEY_ID_123456"); map.put("verification-key-pem",wechatPublic);
        map.put("notify-url","https://example.test/api/membership/payment/notify"); map.put("allowed-platforms",List.of("android"));
        return JSON.writeValueAsString(Map.of("tradepass",Map.of("payment",map)));
    }
    private Map<String,String> headers(String body,long time,String serial) {
        String timestamp=Long.toString(time),nonce="nonce";
        return Map.of("Wechatpay-Timestamp",timestamp,"Wechatpay-Nonce",nonce,"Wechatpay-Serial",serial,
                "Wechatpay-Signature",WechatPayCrypto.sign(timestamp+"\n"+nonce+"\n"+body+"\n",wechatPrivate));
    }
    @Test void signingAndVerificationUseOriginalBodyAndRejectTamperingReplayAndProbeTraffic() throws Exception {
        var settings=WechatPaySettings.parse(config(true));
        String body="{\"total\":69900}";
        var headers=headers(body,CLOCK.instant().getEpochSecond(),settings.verificationKeyId());
        WechatPayCrypto.verify(headers,body,settings,CLOCK);
        WechatPayCrypto.verify(headers("",CLOCK.instant().getEpochSecond(),settings.verificationKeyId()),"",settings,CLOCK);
        assertThatThrownBy(()->WechatPayCrypto.verify(headers,body+" ",settings,CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->WechatPayCrypto.verify(headers(body,CLOCK.instant().getEpochSecond()-301,settings.verificationKeyId()),body,settings,CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->WechatPayCrypto.verify(headers(body,CLOCK.instant().getEpochSecond(),"PUB_KEY_ID_OTHER"),body,settings,CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        var probe=new HashMap<>(headers); probe.put("Wechatpay-Signature","WECHATPAY/SIGNTEST/fake");
        assertThatThrownBy(()->WechatPayCrypto.verify(probe,body,settings,CLOCK)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disablingOrDeletingConfigurationStopsSalesButRetainsInFlightVerificationKeys() throws Exception {
        var store=new WechatPayConfigStore(); store.apply(config(true));
        assertThat(store.current().readyForSale()).isTrue();
        store.apply("tradepass: {payment: {enabled: false}}");
        assertThat(store.current().readyForSale()).isFalse();
        assertThat(store.current().verificationReady()).isTrue();
        assertThat(store.current().requestReady()).isTrue();
        assertThat(store.current().toString()).doesNotContain(merchantPrivate,"0123456789abcdef");
        store.apply(config(true));
        assertThatThrownBy(()->store.apply("tradepass: {payment: {enabled: true}}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.current().readyForSale()).isTrue();
        store.apply(null); assertThat(store.current().readyForSale()).isFalse();
        assertThat(store.current().verificationReady()).isTrue();
    }
    @Test void prepayUsesServerAmountExpiryAndRsaSignedNativePaymentParameters() throws Exception {
        var store=new WechatPayConfigStore(); store.apply(config(true));
        var gateway=new WechatPayGateway(store,(method,path,body,authorization)->{
            assertThat(method).isEqualTo("POST"); assertThat(path).isEqualTo("/v3/pay/transactions/jsapi");
            var request=JSON.readTree(body);
            assertThat(request.path("amount").path("total").asInt()).isEqualTo(69900);
            assertThat(request.path("payer").path("openid").asText()).isEqualTo("server-openid");
            assertThat(request.path("time_expire").asText()).isEqualTo("2026-10-10T16:15:00+08:00");
            assertThat(authorization).startsWith("WECHATPAY2-SHA256-RSA2048").contains("mchid=\"1234567890\"");
            String response="{\"prepay_id\":\"prepay-test\"}";
            return new WechatPayGateway.Reply(200,headers(response,CLOCK.instant().getEpochSecond(),store.current().verificationKeyId()),response);
        },CLOCK);
        String id=gateway.prepay("MP"+"a".repeat(30),"企业VIP",69900,"server-openid",CLOCK.instant().plusSeconds(900),
                store.current().appId(),store.current().mchId());
        var params=gateway.paymentParams(id,store.current().appId(),store.current().mchId());
        assertThat(params.get("signType")).isEqualTo("RSA");
        String message=store.current().appId()+"\n"+params.get("timeStamp")+"\n"+params.get("nonceStr")+"\n"+params.get("package")+"\n";
        var verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(WechatPayCrypto.publicKey(merchantPublic));
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(params.get("paySign")))).isTrue();
    }
    @Test void encryptedNotificationIsAuthenticatedAndAmountIsStrictlyIntegral() throws Exception {
        var store=new WechatPayConfigStore(); store.apply(config(true));
        var gateway=new WechatPayGateway(store,(a,b,c,d)->{throw new AssertionError("no API request");},CLOCK);
        String transaction=JSON.writeValueAsString(Map.of("out_trade_no","MP"+"a".repeat(30),"transaction_id","tx",
                "trade_state","SUCCESS","success_time","2026-10-10T16:00:00+08:00","amount",Map.of("total",69900,"currency","CNY")));
        String body=notification(transaction,store.current().apiV3Key());
        var result=gateway.notification(headers(body,CLOCK.instant().getEpochSecond(),store.current().verificationKeyId()),body);
        assertThat(result.transaction().amountFen()).isEqualTo(69900);
        String fractional=notification(transaction.replace("69900","69900.5"),store.current().apiV3Key());
        assertThat(gateway.notification(headers(fractional,CLOCK.instant().getEpochSecond(),store.current().verificationKeyId()),fractional)
                .transaction().amountFen()).isEqualTo(-1);
        assertThatThrownBy(()->WechatPayCrypto.decrypt("invalid","0123456789ab","transaction",store.current().apiV3Key()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private String notification(String transaction,String key) throws Exception {
        String nonce="0123456789ab",associated="transaction";
        var cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
        String encrypted=Base64.getEncoder().encodeToString(cipher.doFinal(transaction.getBytes(StandardCharsets.UTF_8)));
        return JSON.writeValueAsString(Map.of("id","event-1","event_type","TRANSACTION.SUCCESS","resource",
                Map.of("algorithm","AEAD_AES_256_GCM","ciphertext",encrypted,"nonce",nonce,"associated_data",associated)));
    }
}
