package com.tradepass.module.contract.api.abolish;


/** In-process domain contract; implementations retain the original transaction semantics. */
public interface ContractAbolishRecoveryOperations {
    public void resumeAfterBilateralApproval(Long contractId);
}
