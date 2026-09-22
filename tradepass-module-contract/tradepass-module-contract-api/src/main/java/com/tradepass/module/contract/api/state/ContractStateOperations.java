package com.tradepass.module.contract.api.state;

public interface ContractStateOperations {
    public long electronicTaskCount(Long contractId);
    public int changeActiveStatus(Long contractId, String nextStatus);
}
