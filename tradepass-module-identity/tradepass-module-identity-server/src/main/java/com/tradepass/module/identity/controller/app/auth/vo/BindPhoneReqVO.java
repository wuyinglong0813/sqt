package com.tradepass.module.identity.controller.app.auth.vo;

public record BindPhoneReqVO(String phone, String phoneCode) {
    public BindPhoneReqVO(String phone) {
        this(phone, null);
    }
}
