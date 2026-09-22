package com.tradepass.module.contract.controller.app.signing;

import com.tradepass.module.contract.framework.callback.FadadaCallbackService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fadada")
public class FadadaCallbackController {
    private final FadadaCallbackService callbackService;

    public FadadaCallbackController(FadadaCallbackService callbackService) { this.callbackService = callbackService; }

    @PostMapping(value = "/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> callback(@RequestHeader HttpHeaders headers,
                                           @RequestParam("bizContent") String bizContent) {
        callbackService.accept(headers, bizContent);
        return ResponseEntity.ok("{\"msg\":\"success\"}");
    }
}
