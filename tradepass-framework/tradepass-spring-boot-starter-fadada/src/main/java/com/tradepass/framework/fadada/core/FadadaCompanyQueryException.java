package com.tradepass.framework.fadada.core;

import com.tradepass.framework.common.exception.BusinessException;

public class FadadaCompanyQueryException extends BusinessException {
    private final String providerCode;
    public FadadaCompanyQueryException(String code) {
        super(switch (code) {
            case "210002" -> "企业已在电子签平台授权，请刷新认证结果，无需重复开通";
            case "210032" -> "尚未查询到当前企业的授权记录，请稍后刷新；如已开通，请联系管理员核对授权关联";
            default -> "认证查询过于频繁，请至少等待30秒后刷新";
        });
        this.providerCode = code;
    }
    public String providerCode() { return providerCode; }
}
