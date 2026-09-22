package com.tradepass.module.contract.service.contract;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import com.tradepass.module.contract.convert.contract.TradeContractConvert;
import com.tradepass.module.contract.dal.mysql.contract.TradeContractMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.Serializable;

@Service
@Primary
@ConditionalOnProperty(name = "tradepass.services.split", havingValue = "true")
public class ContractReadServiceImpl implements ContractReadService, ContractReader {
    private final TradeContractMapper contracts;
    private final JdbcTemplate jdbc;
    public ContractReadServiceImpl(TradeContractMapper contracts, JdbcTemplate jdbc) { this.contracts = contracts; this.jdbc = jdbc; }
    @Transactional
    public TradeContractRespDTO selectById(Serializable id) {
        if (id == null) return null;
        // A locking read lets Seata wait for an in-flight global writer before returning state.
        return TradeContractConvert.INSTANCE.toDTO(contracts.selectByIdForUpdate(Long.valueOf(id.toString())));
    }
    @Transactional
    public TradeContractRespDTO selectByIdForUpdate(Long id) {
        // AT releases the local connection when the HTTP call returns. Register a global row
        // lock with a no-op update so the original exclusive lock survives until root completion.
        jdbc.update("UPDATE trade_contract SET created_at = created_at WHERE id = ?", id);
        return TradeContractConvert.INSTANCE.toDTO(contracts.selectByIdForUpdate(id));
    }
    public long countContractsAwaitingSignature(long companyId) { return contracts.countContractsAwaitingSignature(companyId); }
}
