package com.tradepass.module.identity.api.counterparty;

public interface CounterpartyReader {
    public long countActiveBetween(Long leftCompanyId, Long rightCompanyId);
}
