package com.tradepass.module.contract.service.payment;

import org.springframework.stereotype.Service;

@Service
public class WechatPayConfigStore {
    private volatile WechatPaySettings value=WechatPaySettings.empty();
    private volatile boolean refreshFailed;
    public WechatPaySettings current() { return value; }
    public boolean refreshFailed() { return refreshFailed; }
    public void failed() { refreshFailed=true; }
    public void apply(String content) {
        WechatPaySettings next=WechatPaySettings.parse(content);
        // Disabling/removing new payment must not discard the keys needed by pending callbacks.
        if (!next.requestReady() && value.verificationReady() && next.privateKeyPem().isBlank()
                && next.apiV3Key().isBlank() && next.verificationKeyPem().isBlank()) {
            var old=value;
            next=new WechatPaySettings(false,old.appId(),old.mchId(),old.merchantSerial(),old.privateKeyPem(),
                    old.apiV3Key(),old.verificationKeyId(),old.verificationKeyPem(),old.notifyUrl(),java.util.Set.of());
        }
        value=next;
        refreshFailed=false;
    }
}
