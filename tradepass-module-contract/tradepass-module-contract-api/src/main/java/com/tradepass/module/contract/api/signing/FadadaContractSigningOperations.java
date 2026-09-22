package com.tradepass.module.contract.api.signing;

import com.tradepass.module.contract.api.signing.dto.ContractSigningRespDTO;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface FadadaContractSigningOperations {
    public ContractSigningRespDTO current(Long contractId);

    public ServiceUrlPayload signUrl(Long contractId);

    public ContractSigningRespDTO syncCurrent(Long contractId);

    public SignedPreview signedPreview(Long contractId);

    public void syncBySignTaskId(String signTaskId);

    public ServiceUrlPayload abolishUrl(Long contractId, String reason);

    public record SignedPreview(String fileName, byte[] data) {
    }
}
