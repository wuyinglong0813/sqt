package com.tradepass.module.trade.api.document;

import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import com.tradepass.module.trade.convert.document.BusinessDocumentConvert;
import com.tradepass.module.trade.dal.mysql.document.BusinessDocumentMapper;
import org.springframework.stereotype.Service;

@Service
public class DocumentTodoReaderImpl implements DocumentTodoReader {
    private final BusinessDocumentMapper mapper;
    public DocumentTodoReaderImpl(BusinessDocumentMapper mapper) { this.mapper = mapper; }
    @Override public long pendingDocumentCount(long companyId) { return mapper.pendingDocumentCount(companyId); }
    @Override public BusinessDocumentRespDTO latestPendingDocument(long companyId) { return BusinessDocumentConvert.INSTANCE.toDTO(mapper.latestPendingDocument(companyId)); }
}
