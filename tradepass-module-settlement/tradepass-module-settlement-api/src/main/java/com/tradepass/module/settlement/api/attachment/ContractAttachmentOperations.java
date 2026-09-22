package com.tradepass.module.settlement.api.attachment;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.settlement.enums.AttachmentCategoryEnum;
import java.util.List;
import java.util.Map;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface ContractAttachmentOperations {
    String PAYMENT_VOUCHER = AttachmentCategoryEnum.PAYMENT_VOUCHER.getCategory();
    String INVOICE = AttachmentCategoryEnum.INVOICE.getCategory();
    String OTHER = AttachmentCategoryEnum.OTHER.getCategory();

    List<Map<String, Object>> list(Long contractId, String category);

    Map<String, Object> upload(Long contractId, String category, String originalName,
                                      byte[] data, String voucherDate, String voucherAmount);

    Map<String, Object> upload(Long contractId, String category, String originalName,
                                      byte[] data, String voucherDate, String voucherAmount,
                                      String invoiceNo, String invoiceDate, String invoiceAmount);

    Map<String, Object> decide(Long id, String decision, String reason);

    Map<String, Object> decide(Long id, String decision, String reason,
                                      String signatureName, byte[] signatureData);

    String withdraw(Long id);

    String delete(Long id);

    FileRespDTO getFile(Long id);

    record FileRespDTO(Long id, Long contractId, String originalName, String contentType,
                              byte[] data, String storageBucket, String objectKey,
                              String objectVersionId, Long fileSize, String sha256) {
        public FileRespDTO(Long id, Long contractId, String originalName,
                           String contentType, byte[] data) {
            this(id, contractId, originalName, contentType, data,
                    null, null, null, data == null ? null : (long) data.length,
                    data == null ? null : FileTypeInspector.sha256(data));
        }

        public FileRespDTO withData(byte[] value) {
            return new FileRespDTO(id, contractId, originalName, contentType, value,
                    storageBucket, objectKey, objectVersionId, fileSize, sha256);
        }
    }
}
