package com.tradepass.module.settlement.api.attachment;

import com.tradepass.framework.common.util.FileTypeInspector;
import java.util.List;
import java.util.Map;
import com.tradepass.module.settlement.service.attachment.ContractAttachmentService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class ContractAttachmentOperationsImpl implements ContractAttachmentOperations {
    private final ContractAttachmentService delegate;
    public ContractAttachmentOperationsImpl(@Lazy ContractAttachmentService delegate) { this.delegate = delegate; }
    @Override public List<Map<String, Object>> list(Long contractId, String category) { return delegate.list(contractId, category); }
    @Override public Map<String, Object> upload(Long contractId, String category, String originalName,
                                      byte[] data, String voucherDate, String voucherAmount) { return delegate.upload(contractId, category, originalName, data, voucherDate, voucherAmount); }
    @Override public Map<String, Object> upload(Long contractId, String category, String originalName,
                                      byte[] data, String voucherDate, String voucherAmount,
                                      String invoiceNo, String invoiceDate, String invoiceAmount) { return delegate.upload(contractId, category, originalName, data, voucherDate, voucherAmount, invoiceNo, invoiceDate, invoiceAmount); }
    @Override public Map<String, Object> decide(Long id, String decision, String reason) { return delegate.decide(id, decision, reason); }
    @Override public Map<String, Object> decide(Long id, String decision, String reason,
                                      String signatureName, byte[] signatureData) { return delegate.decide(id, decision, reason, signatureName, signatureData); }
    @Override public String withdraw(Long id) { return delegate.withdraw(id); }
    @Override public String delete(Long id) { return delegate.delete(id); }
    @Override public FileRespDTO getFile(Long id) { return delegate.getFile(id); }
}
