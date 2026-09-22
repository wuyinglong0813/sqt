package com.tradepass.module.trade.service.inventory;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.storage.config.StorageProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface SalesOrderSignatureService {
    public static final long MAX_SIGNATURE_SIZE = 2L * 1024 * 1024;

    public record Confirmation(String signerName, LocalDateTime signedAt,
                                   String originalName, String contentType, byte[] data) {
        }

    Confirmation save(long companyId, Long documentId, Long receiptId, String signerName, String originalName, byte[] data);
    Confirmation find(Long documentId);
}
