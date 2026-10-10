package com.tradepass.module.contract.service.payment;

import com.fasterxml.jackson.databind.*;
import com.tradepass.framework.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Service
public class WechatPayGateway {
    private static final class ApiFailure extends BusinessException {
        final String code;
        ApiFailure(String code) { super("微信支付请求未完成，请刷新订单后重试"); this.code=code; }
    }
    public record Reply(int status,Map<String,String> headers,String body) { }
    @FunctionalInterface public interface Transport { Reply send(String method,String path,String body,String authorization) throws Exception; }
    public record Transaction(String orderNo,String transactionId,String state,String appId,String mchId,String openid,
                              int amountFen,String currency,String tradeType,Instant successTime) { }
    public record Notice(String eventId,Transaction transaction) { }
    private final WechatPayConfigStore settings;
    private final Transport transport;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper();

    @Autowired public WechatPayGateway(WechatPayConfigStore settings) {
        this(settings,new HttpTransport(),Clock.systemUTC());
    }
    public WechatPayGateway(WechatPayConfigStore settings,Transport transport,Clock clock) {
        this.settings=settings; this.transport=transport; this.clock=clock;
    }
    public WechatPaySettings settings() { return settings.current(); }
    public boolean ready() { return settings().readyForSale(); }
    public boolean platformAllowed(String platform) { return settings().allowedPlatforms().contains(platform); }

    public String prepay(String orderNo,String name,int amount,String openid,Instant expires,String appId,String mchId) {
        var config=settings();
        if (!config.readyForSale()) throw new BusinessException("购买暂未开放");
        sameMerchant(config,appId,mchId);
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("appid",appId); body.put("mchid",mchId); body.put("description",name);
        body.put("out_trade_no",orderNo); body.put("notify_url",config.notifyUrl());
        body.put("time_expire",java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")
                .format(expires.atZone(ZoneId.of("Asia/Shanghai"))));
        body.put("amount",Map.of("total",amount,"currency","CNY")); body.put("payer",Map.of("openid",openid));
        JsonNode response=call(config,"POST","/v3/pay/transactions/jsapi",write(body));
        String id=response.path("prepay_id").asText();
        if (id.isBlank() || id.length()>128) throw new BusinessException("未取得支付会话，请稍后查看订单");
        return id;
    }
    public Map<String,String> paymentParams(String prepayId,String appId,String mchId) {
        var config=settings(); sameMerchant(config,appId,mchId);
        String timestamp=Long.toString(clock.instant().getEpochSecond()),nonce=UUID.randomUUID().toString().replace("-","");
        String packageValue="prepay_id="+prepayId;
        return Map.of("timeStamp",timestamp,"nonceStr",nonce,"package",packageValue,"signType","RSA",
                "paySign",WechatPayCrypto.sign(appId+"\n"+timestamp+"\n"+nonce+"\n"+packageValue+"\n",config.privateKeyPem()));
    }
    public Transaction query(String orderNo,String appId,String mchId) {
        var config=settings(); sameMerchant(config,appId,mchId);
        try { return transaction(call(config,"GET","/v3/pay/transactions/out-trade-no/"+orderNo+"?mchid="+mchId,"")); }
        catch(ApiFailure e) {
            if (!"ORDER_NOT_EXIST".equals(e.code)) throw e;
            return new Transaction(orderNo,"","NOT_FOUND",appId,mchId,"",-1,"","",null);
        }
    }
    public void close(String orderNo,String appId,String mchId) {
        var config=settings(); sameMerchant(config,appId,mchId);
        call(config,"POST","/v3/pay/transactions/out-trade-no/"+orderNo+"/close",write(Map.of("mchid",mchId)));
    }
    public Notice notification(Map<String,String> headers,String body) {
        var config=settings();
        if (!config.verificationReady()) throw new IllegalStateException("支付验签配置尚未就绪");
        if (body.length()>1_048_576) throw new IllegalArgumentException("支付通知过大");
        WechatPayCrypto.verify(headers,body,config,clock);
        JsonNode event=read(body),resource=event.path("resource");
        String eventId=event.path("id").asText();
        if (eventId.isBlank() || eventId.length()>64 || !"TRANSACTION.SUCCESS".equals(event.path("event_type").asText())
                || !"AEAD_AES_256_GCM".equals(resource.path("algorithm").asText()))
            throw new IllegalArgumentException("支付通知类型无效");
        String decoded=WechatPayCrypto.decrypt(resource.path("ciphertext").asText(),resource.path("nonce").asText(),
                resource.path("associated_data").asText(),config.apiV3Key());
        Transaction transaction=transaction(read(decoded));
        if (!"SUCCESS".equals(transaction.state())) throw new IllegalArgumentException("支付通知状态无效");
        return new Notice(eventId,transaction);
    }
    private Transaction transaction(JsonNode value) {
        Instant time=null;
        if (!value.path("success_time").asText().isBlank()) {
            try { time=OffsetDateTime.parse(value.path("success_time").asText()).toInstant(); }
            catch(RuntimeException e) { throw new IllegalArgumentException("支付完成时间无效"); }
        }
        return new Transaction(value.path("out_trade_no").asText(),value.path("transaction_id").asText(),
                value.path("trade_state").asText(),value.path("appid").asText(),value.path("mchid").asText(),
                value.path("payer").path("openid").asText(),
                value.path("amount").path("total").isIntegralNumber() && value.path("amount").path("total").canConvertToInt()
                    ? value.path("amount").path("total").intValue() : -1,
                value.path("amount").path("currency").asText(),value.path("trade_type").asText(),time);
    }
    private JsonNode call(WechatPaySettings config,String method,String path,String body) {
        if (!config.requestReady()) throw new BusinessException("支付服务暂不可用，请稍后核对订单");
        try {
            String timestamp=Long.toString(clock.instant().getEpochSecond()),nonce=UUID.randomUUID().toString().replace("-","");
            String signature=WechatPayCrypto.sign(method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+body+"\n",config.privateKeyPem());
            String authorization="WECHATPAY2-SHA256-RSA2048 mchid=\""+config.mchId()+"\",nonce_str=\""+nonce
                    +"\",timestamp=\""+timestamp+"\",serial_no=\""+config.merchantSerial()+"\",signature=\""+signature+"\"";
            Reply reply=transport.send(method,path,body,authorization);
            WechatPayCrypto.verify(reply.headers(),reply.body(),config,clock);
            if (reply.status()<200 || reply.status()>=300) throw new ApiFailure(read(reply.body()).path("code").asText());
            return reply.body().isBlank()?json.createObjectNode():read(reply.body());
        } catch(BusinessException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new BusinessException("支付请求中断，请核对订单"); }
        catch(Exception e) { throw new BusinessException("支付服务暂不可用，请稍后核对订单"); }
    }
    private void sameMerchant(WechatPaySettings config,String appId,String mchId) {
        if (!config.appId().equals(appId) || !config.mchId().equals(mchId))
            throw new BusinessException("订单支付商户配置已变化，请联系平台核对");
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException(); } }
    private JsonNode read(String value) {
        try {
            JsonNode result=json.readTree(value);
            if (result==null || !result.isObject()) throw new IllegalArgumentException();
            return result;
        } catch(Exception e) { throw new IllegalArgumentException("支付报文格式无效"); }
    }
    private static final class HttpTransport implements Transport {
        private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        public Reply send(String method,String path,String body,String authorization) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("https://api.mch.weixin.qq.com"+path))
                    .timeout(Duration.ofSeconds(12)).header("Accept","application/json")
                    .header("Content-Type","application/json").header("Authorization",authorization)
                    .method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.body().length()>1_048_576) throw new IllegalArgumentException("支付响应过大");
            Map<String,String> headers=new HashMap<>(); response.headers().map().forEach((key,values)->headers.put(key,values.get(0)));
            return new Reply(response.statusCode(),headers,response.body());
        }
    }
}
