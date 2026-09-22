package com.tradepass.module.identity.api.counterparty;

import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import org.springframework.stereotype.Service;

@Service
public class CounterpartyReaderImpl implements CounterpartyReader {
    private final CounterpartyRelationMapper mapper;
    public CounterpartyReaderImpl(CounterpartyRelationMapper mapper) { this.mapper = mapper; }
    @Override public long countActiveBetween(Long leftCompanyId, Long rightCompanyId) {
        return mapper.countActiveBetween(leftCompanyId, rightCompanyId);
    }
}
