package com.tradepass.module.trade.controller.app.retail;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.pojo.FileDataPayload;
import com.tradepass.framework.common.pojo.PagePayload;
import com.tradepass.module.trade.service.document.BusinessDocumentPdfService;
import com.tradepass.module.trade.service.retail.RetailService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.tradepass.module.trade.service.retail.RetailDtos.*;

@RestController
@RequestMapping("/api/retail")
public class RetailController {
    private final RetailService retail;
    private final BusinessDocumentPdfService pdf;
    public RetailController(RetailService retail, BusinessDocumentPdfService pdf) { this.retail = retail; this.pdf = pdf; }
    @GetMapping("/customers")
    public ApiResponse<Map<String, Object>> customers(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(retail.customers(page, size));
    }
    @GetMapping("/customers/{id}")
    public ApiResponse<Map<String, Object>> customer(@PathVariable Long id) { return ApiResponse.ok(retail.customer(id)); }
    @PostMapping("/customers")
    public ApiResponse<Map<String, Object>> createCustomer(@RequestBody CustomerRequest body) { return ApiResponse.ok(retail.saveCustomer(null, body)); }
    @PostMapping("/customers/{id}")
    public ApiResponse<Map<String, Object>> updateCustomer(@PathVariable Long id, @RequestBody CustomerRequest body) { return ApiResponse.ok(retail.saveCustomer(id, body)); }
    @GetMapping("/customers/{id}/documents")
    public ApiResponse<PagePayload<Map<String, Object>>> documents(@PathVariable Long id,
            @RequestParam(defaultValue = "SALES_ORDER") String documentType, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(retail.documents(id, documentType, status, page, size));
    }
    @PostMapping("/customers/{id}/documents")
    public ApiResponse<Map<String, Object>> createDocument(@PathVariable Long id, @RequestBody DocumentRequest body) { return ApiResponse.ok(retail.createDocument(id, body)); }
    @GetMapping("/documents/{id}")
    public ApiResponse<Map<String, Object>> document(@PathVariable Long id) { return ApiResponse.ok(retail.document(id)); }
    @PostMapping("/documents/{id}/draft")
    public ApiResponse<Map<String, Object>> updateDraft(@PathVariable Long id, @RequestBody DocumentRequest body) { return ApiResponse.ok(retail.updateDraft(id, body)); }
    @PostMapping("/documents/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable Long id, @RequestBody(required = false) ConfirmRequest body) {
        return ApiResponse.ok(retail.confirm(id, body == null ? null : body.warehouseId()));
    }
    @PostMapping("/documents/{id}/stock")
    public ApiResponse<Map<String, Object>> processStock(@PathVariable Long id, @RequestBody ConfirmRequest body) { return ApiResponse.ok(retail.processStock(id, body.warehouseId())); }
    @PostMapping("/documents/{id}/delete")
    public ApiResponse<String> delete(@PathVariable Long id) { return ApiResponse.ok(retail.deleteDraft(id)); }
    @GetMapping("/documents/{id}/pdf-data")
    public ApiResponse<FileDataPayload> pdf(@PathVariable Long id) {
        var document = retail.pdfDocument(id);
        return ApiResponse.ok(FileDataPayload.of(pdf.fileName(document), "application/pdf", pdf.generate(document)));
    }
}
