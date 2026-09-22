package com.tradepass.module.trade.controller.app.inventory;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.service.inventory.SalesOrderInventoryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InventoryController {
    private final SalesOrderInventoryService inventoryService;

    public InventoryController(SalesOrderInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/trade-documents/{id}")
    public ApiResponse<Map<String, Object>> salesOrderDetail(@PathVariable Long id) {
        return ApiResponse.ok(inventoryService.documentDetail(id));
    }

    @PostMapping(value = "/sales-orders/{id}/receive", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> receive(@PathVariable Long id,
                                                    @RequestBody Map<String, Object> body) {
        byte[] signature = decodeSignature(body.get("signatureBase64"));
        return ApiResponse.ok(inventoryService.receive(id,
                String.valueOf(body.getOrDefault("decision", "")),
                longValue(body.get("warehouseId")),
                String.valueOf(body.getOrDefault("reason", "")),
                signature == null ? null : "signature.png", signature));
    }

    @PostMapping(value = "/trade-documents/{id}/receive", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> confirmDocument(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        byte[] signature = decodeSignature(body.get("signatureBase64"));
        return ApiResponse.ok(inventoryService.receive(id,
                String.valueOf(body.getOrDefault("decision", "")),
                longValue(body.get("warehouseId")),
                String.valueOf(body.getOrDefault("reason", "")),
                signature == null ? null : "signature.png", signature));
    }

    @PostMapping(value = "/sales-orders/{id}/receive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> receiveWithSignature(
            @PathVariable Long id,
            @RequestParam String decision,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam("signature") MultipartFile signature) {
        try {
            return ApiResponse.ok(inventoryService.receive(id, decision, warehouseId, "",
                    signature.getOriginalFilename(), signature.getBytes()));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("签名图片读取失败，请重新签名");
        }
    }

    @PostMapping(value = "/trade-documents/{id}/receive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> confirmDocumentWithSignature(
            @PathVariable Long id,
            @RequestParam String decision,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam("signature") MultipartFile signature) {
        try {
            return ApiResponse.ok(inventoryService.receive(id, decision, warehouseId, "",
                    signature.getOriginalFilename(), signature.getBytes()));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("签名图片读取失败，请重新签名");
        }
    }

    static byte[] decodeSignature(Object value) {
        if (value == null) return null;
        if (!(value instanceof String encoded) || encoded.isBlank() || encoded.length() > 700000) {
            throw new BusinessException("签名图片为空或过大，请重新签名");
        }
        try {
            return java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("签名图片格式不正确，请重新签名");
        }
    }

    public record ManualInboundRequest(Long warehouseId, String requestId, String productName,
            String specification, String baseUnit, java.math.BigDecimal quantity,
            java.math.BigDecimal unitPrice, String remark) {}

    @PostMapping("/inventory/manual-inbound")
    public ApiResponse<Map<String, Object>> manualInbound(@RequestBody ManualInboundRequest body) {
        return ApiResponse.ok(inventoryService.manualInbound(body.warehouseId(), body.requestId(),
                body.productName(), body.specification(), body.baseUnit(), body.quantity(), body.unitPrice(), body.remark()));
    }

    @GetMapping("/warehouses")
    public ApiResponse<List<Map<String, Object>>> warehouses() {
        return ApiResponse.ok(inventoryService.listWarehouses());
    }

    @PostMapping("/warehouses")
    public ApiResponse<Map<String, Object>> createWarehouse(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(inventoryService.createWarehouse(
                String.valueOf(body.getOrDefault("name", "")),
                String.valueOf(body.getOrDefault("address", ""))));
    }

    @GetMapping("/inventory/overview")
    public ApiResponse<Map<String, Object>> inventoryOverview() {
        return ApiResponse.ok(inventoryService.inventoryOverview());
    }

    @GetMapping("/inventory/products")
    public ApiResponse<List<Map<String, Object>>> inventoryProducts(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        return ApiResponse.ok(inventoryService.searchProducts(keyword, limit));
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new BusinessException("仓库 ID 格式不正确");
        }
    }
}
