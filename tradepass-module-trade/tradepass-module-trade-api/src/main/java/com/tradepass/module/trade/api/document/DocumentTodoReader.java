package com.tradepass.module.trade.api.document;

import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;

public interface DocumentTodoReader {
    public long pendingDocumentCount(long companyId);
    public BusinessDocumentRespDTO latestPendingDocument(long companyId);
}
