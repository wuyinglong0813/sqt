package com.tradepass.framework.fadada.core;

import com.tradepass.framework.common.exception.BusinessException;

/** Controlled explanation for official provider code 212010; never contains provider free text. */
public class FadadaProviderQuotaException extends BusinessException {
    public static final String MESSAGE = "当前企业可用签署额度不足，请联系平台核对共享配置";

    public FadadaProviderQuotaException() { super(MESSAGE); }
}
