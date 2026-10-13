package com.tradepass.framework.fadada.core;

import com.tradepass.framework.common.exception.BusinessException;

public class FadadaUserQueryException extends BusinessException {
    private final String providerCode;

    public FadadaUserQueryException(String providerCode) {
        super(switch (providerCode) {
            case "210002" -> "个人账号已授权，正在同步认证结果";
            case "210022" -> "尚未查询到个人授权，请完成认证页面后再刷新；如已开通，请联系管理员核对账号标识或禁用状态";
            default -> "认证查询过于频繁，请稍后再刷新";
        });
        this.providerCode = providerCode;
    }

    public String providerCode() { return providerCode; }
}
