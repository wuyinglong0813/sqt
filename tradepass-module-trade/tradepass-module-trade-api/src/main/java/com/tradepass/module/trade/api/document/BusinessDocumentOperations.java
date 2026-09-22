package com.tradepass.module.trade.api.document;

import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import java.util.List;
import java.util.Map;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface BusinessDocumentOperations {
    public static final String SALES_ORDER = "SALES_ORDER";

    public static final String RETURN_ORDER = "RETURN_ORDER";

    public List<Map<String, Object>> listTemplates(String type);

    public Map<String, Object> createTemplate(Map<String, Object> body);

    public String deleteTemplate(Long id);

    public List<Map<String, Object>> listDocuments(Long contractId, String type);

    public Map<String, Object> createDocument(Long contractId, Map<String, Object> body);

    public Map<String, Object> updateDraft(Long id, Map<String, Object> body);

    public Map<String, Object> publishDraft(Long id);

    public Map<String, Object> publishDraft(Long id, Long warehouseId);

    public BusinessDocumentRespDTO getDocument(Long id);

    public String deleteDraft(Long id);

    public String withdraw(Long id);

    public String typeLabel(String type);

    public String defaultTemplateContent(String type);
}
