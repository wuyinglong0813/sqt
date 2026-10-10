package com.tradepass.module.contract.service.payment;

import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Secrets live only in the dedicated Nacos document and process memory, never in policy history. */
public record WechatPaySettings(boolean enabled, String appId, String mchId, String merchantSerial,
                                String privateKeyPem, String apiV3Key, String verificationKeyId,
                                String verificationKeyPem, String notifyUrl, Set<String> allowedPlatforms) {
    public static WechatPaySettings empty() {
        return new WechatPaySettings(false,"","","","","","","","",Set.of());
    }
    public boolean verificationReady() { return !apiV3Key.isBlank() && !verificationKeyId.isBlank() && !verificationKeyPem.isBlank(); }
    public boolean requestReady() { return verificationReady() && !appId.isBlank() && !mchId.isBlank()
            && !merchantSerial.isBlank() && !privateKeyPem.isBlank() && !notifyUrl.isBlank(); }
    public boolean readyForSale() { return enabled && requestReady() && !allowedPlatforms.isEmpty(); }
    @Override public String toString() { return "WechatPaySettings[redacted]"; }

    public static WechatPaySettings parse(String content) {
        if (content == null || content.isBlank()) return empty();
        LoaderOptions options=new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setCodePointLimit(65536);
        Map<?,?> root=map(new Yaml(new SafeConstructor(options)).load(content));
        check(root,"tradepass"); var parent=map(root.get("tradepass")); check(parent,"payment");
        if (!root.isEmpty() && !parent.containsKey("payment")) throw new IllegalArgumentException("缺少tradepass.payment");
        var config=map(parent.get("payment"));
        check(config,"enabled","channel","app-id","mch-id","merchant-serial","merchant-private-key-pem",
                "api-v3-key","verification-key-id","verification-key-pem","notify-url","allowed-platforms");
        if (config.containsKey("channel") && !"WECHAT_JSAPI".equals(text(config.get("channel"))))
            throw new IllegalArgumentException("目前支付channel支持WECHAT_JSAPI");
        Object flag=config.get("enabled");
        if (flag != null && !(flag instanceof Boolean)) throw new IllegalArgumentException("payment.enabled必须是布尔值");
        Set<String> platforms=new LinkedHashSet<>();
        Object list=config.get("allowed-platforms");
        if (list != null) {
            if (!(list instanceof List<?> items)) throw new IllegalArgumentException("allowed-platforms必须是列表");
            for (Object item:items) {
                String platform=text(item);
                if (!Set.of("android","ios","windows","mac","devtools","harmony").contains(platform))
                    throw new IllegalArgumentException("allowed-platforms包含不支持的客户端");
                platforms.add(platform);
            }
        }
        var value=new WechatPaySettings(Boolean.TRUE.equals(flag),text(config.get("app-id")),text(config.get("mch-id")),
                text(config.get("merchant-serial")),text(config.get("merchant-private-key-pem")),text(config.get("api-v3-key")),
                text(config.get("verification-key-id")),text(config.get("verification-key-pem")),text(config.get("notify-url")),
                Collections.unmodifiableSet(platforms));
        if (!value.appId().isBlank() && !value.appId().matches("wx[A-Za-z0-9]{1,30}")) throw new IllegalArgumentException("支付AppID格式无效");
        if (!value.mchId().isBlank() && !value.mchId().matches("[0-9]{1,32}")) throw new IllegalArgumentException("支付商户号格式无效");
        if (!value.merchantSerial().isBlank() && !value.merchantSerial().matches("[A-Fa-f0-9]{16,64}"))
            throw new IllegalArgumentException("商户证书序列号格式无效");
        if (!value.apiV3Key().isBlank() && value.apiV3Key().getBytes(StandardCharsets.UTF_8).length!=32)
            throw new IllegalArgumentException("APIv3密钥必须为32字节");
        if (!value.notifyUrl().isBlank()) {
            URI uri;
            try { uri=URI.create(value.notifyUrl()); } catch(RuntimeException e) { throw new IllegalArgumentException("回调URL格式无效"); }
            if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null || value.notifyUrl().length()>255)
                throw new IllegalArgumentException("回调URL必须是无参数的HTTPS地址");
        }
        if (!value.privateKeyPem().isBlank()) WechatPayCrypto.privateKey(value.privateKeyPem());
        if (!value.verificationKeyPem().isBlank()) WechatPayCrypto.publicKey(value.verificationKeyPem());
        if (!value.verificationKeyId().isBlank() && !value.verificationKeyId().matches("[A-Za-z0-9_]{8,80}"))
            throw new IllegalArgumentException("微信验签公钥ID或平台证书序列号无效");
        if (value.enabled() && !value.requestReady()) throw new IllegalArgumentException("开启支付前必须补齐商户和验签参数");
        return value;
    }
    private static String text(Object raw) { return raw==null?"":raw.toString().trim(); }
    private static Map<?,?> map(Object raw) {
        if (raw==null) return Map.of();
        if (!(raw instanceof Map<?,?> value)) throw new IllegalArgumentException("支付配置必须是YAML对象");
        return value;
    }
    private static void check(Map<?,?> values,String... keys) {
        var valid=Set.of(keys);
        for(Object key:values.keySet()) if(!valid.contains(key.toString())) throw new IllegalArgumentException("未知支付配置字段");
    }
}
