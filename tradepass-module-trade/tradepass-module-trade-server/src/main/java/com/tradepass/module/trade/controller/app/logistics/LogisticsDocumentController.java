package com.tradepass.module.trade.controller.app.logistics;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.trade.dal.dataobject.logistics.LogisticsDocumentDO;
import com.tradepass.framework.common.pojo.FileChunkDataPayload;
import com.tradepass.framework.common.pojo.FileDataPayload;
import com.tradepass.module.trade.service.logistics.LogisticsDocumentService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Arrays;

@RestController
@RequestMapping("/api")
public class LogisticsDocumentController {
    private final LogisticsDocumentService logisticsDocumentService;

    public LogisticsDocumentController(LogisticsDocumentService logisticsDocumentService) {
        this.logisticsDocumentService = logisticsDocumentService;
    }

    @GetMapping("/contracts/{contractId}/logistics-documents")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable Long contractId) {
        return ApiResponse.ok(logisticsDocumentService.listDocuments(contractId));
    }

    @PostMapping(value = "/contracts/{contractId}/logistics-documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> upload(@PathVariable Long contractId,
                                                   @RequestParam(value = "originalName", required = false)
                                                   String originalName,
                                                   @RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.ok(logisticsDocumentService.upload(
                    contractId,
                    originalName == null || originalName.isBlank()
                            ? file.getOriginalFilename() : originalName,
                    file.getBytes()));
        } catch (IOException exception) {
            throw new BusinessException("物流单图片读取失败，请重新选择");
        }
    }

    @PostMapping(value = "/contracts/{contractId}/logistics-documents/base64",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> uploadBase64(@PathVariable Long contractId,
                                                         @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(logisticsDocumentService.upload(contractId,
                String.valueOf(body.getOrDefault("originalName", "物流单.jpg")),
                decodeBase64(body.get("contentBase64"))));
    }

    @GetMapping("/logistics-documents/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        LogisticsDocumentDO document = logisticsDocumentService.getImage(id);
        byte[] imageData = document.getImageData();
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(document.getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(imageData.length)
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .body(imageData);
    }

    @GetMapping("/logistics-documents/{id}/image-data")
    public ApiResponse<FileDataPayload> imageData(@PathVariable Long id) {
        LogisticsDocumentDO document = logisticsDocumentService.getImage(id);
        return ApiResponse.ok(FileDataPayload.of(
                document.getOriginalName(), document.getContentType(), document.getImageData()));
    }

    @GetMapping("/logistics-documents/{id}/image-chunk-data")
    public ApiResponse<FileChunkDataPayload> imageChunkData(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "655360") int size) {
        if (offset < 0 || size <= 0 || size > 640 * 1024) {
            throw new BusinessException("文件分片参数不正确");
        }
        LogisticsDocumentDO document = logisticsDocumentService.getImage(id);
        byte[] data = document.getImageData();
        if (offset >= data.length) {
            throw new BusinessException("文件分片位置超出范围");
        }
        int start = (int) offset;
        int end = Math.min(data.length, start + size);
        return ApiResponse.ok(FileChunkDataPayload.of(
                document.getOriginalName(), document.getContentType(),
                Arrays.copyOfRange(data, start, end),
                offset, data.length, end == data.length));
    }

    @PostMapping("/logistics-documents/{id}/delete")
    public ApiResponse<String> delete(@PathVariable Long id) {
        return ApiResponse.ok(logisticsDocumentService.delete(id));
    }

    private byte[] decodeBase64(Object value) {
        try {
            return Base64.getDecoder().decode(value == null ? "" : String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("物流单图片读取失败，请重新选择");
        }
    }
}
