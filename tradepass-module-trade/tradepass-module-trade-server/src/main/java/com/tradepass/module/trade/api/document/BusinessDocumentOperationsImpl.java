package com.tradepass.module.trade.api.document;

import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import java.util.List;
import java.util.Map;
import com.tradepass.module.trade.service.document.BusinessDocumentService;
import com.tradepass.module.trade.convert.document.BusinessDocumentConvert;
import org.springframework.stereotype.Service;

@Service
public class BusinessDocumentOperationsImpl implements BusinessDocumentOperations {
    private final BusinessDocumentService delegate;
    public BusinessDocumentOperationsImpl(BusinessDocumentService delegate) { this.delegate = delegate; }
    @Override public List<Map<String, Object>> listTemplates(String type) { return delegate.listTemplates(type); }
    @Override public Map<String, Object> createTemplate(Map<String, Object> body) { return delegate.createTemplate(body); }
    @Override public String deleteTemplate(Long id) { return delegate.deleteTemplate(id); }
    @Override public List<Map<String, Object>> listDocuments(Long contractId, String type) { return delegate.listDocuments(contractId, type); }
    @Override public Map<String, Object> createDocument(Long contractId, Map<String, Object> body) { return delegate.createDocument(contractId, body); }
    @Override public Map<String, Object> updateDraft(Long id, Map<String, Object> body) { return delegate.updateDraft(id, body); }
    @Override public Map<String, Object> publishDraft(Long id) { return delegate.publishDraft(id); }
    @Override public Map<String, Object> publishDraft(Long id, Long warehouseId) { return delegate.publishDraft(id, warehouseId); }
    @Override public BusinessDocumentRespDTO getDocument(Long id) { return BusinessDocumentConvert.INSTANCE.toDTO(delegate.getDocument(id)); }
    @Override public String deleteDraft(Long id) { return delegate.deleteDraft(id); }
    @Override public String withdraw(Long id) { return delegate.withdraw(id); }
    @Override public String typeLabel(String type) { return delegate.typeLabel(type); }
    @Override public String defaultTemplateContent(String type) { return delegate.defaultTemplateContent(type); }
}
