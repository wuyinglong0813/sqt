package com.tradepass.framework.web.core.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProbeController {
    static final String WECHAT_BUSINESS_DOMAIN_FILE = "2abd5ec6ebdeca15502051ee9e3b888b";

    @GetMapping("/tcb_probe")
    public ResponseEntity<Void> probe() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/CqVT3HIuWt.txt")
    public ResponseEntity<String> wechatBusinessDomainFile() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(WECHAT_BUSINESS_DOMAIN_FILE);
    }
}
