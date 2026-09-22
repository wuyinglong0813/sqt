package com.tradepass.module.trade.api.bilateral;

import com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO;
import java.util.Map;
import com.tradepass.module.trade.service.bilateral.BilateralActionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class BilateralActionOperationsImpl implements BilateralActionOperations {
    private final BilateralActionService delegate;
    public BilateralActionOperationsImpl(@Lazy BilateralActionService delegate) { this.delegate = delegate; }
    @Override public Map<String, Object> request(String bizType, Long bizId, String actionType,
                                       String reason, boolean riskConfirmed) { return delegate.request(bizType, bizId, actionType, reason, riskConfirmed); }
    @Override public Map<String, Object> decide(Long id, String decision, String reason) { return delegate.decide(id, decision, reason); }
    @Override public String cancel(Long id) { return delegate.cancel(id); }
    @Override public Map<String, Object> active(String bizType, Long bizId) { return delegate.active(bizType, bizId); }
    @Override public ActionState state(long companyId, String bizType, Long bizId) { return delegate.state(companyId, bizType, bizId); }
    @Override public void requireContractMutable(TradeContractRespDTO contract) { delegate.requireContractMutable(contract); }
    @Override public boolean isContractReadOnly(TradeContractRespDTO contract) { return delegate.isContractReadOnly(contract); }
}
