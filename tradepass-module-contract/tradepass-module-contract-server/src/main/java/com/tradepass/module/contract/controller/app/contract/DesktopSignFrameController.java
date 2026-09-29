package com.tradepass.module.contract.controller.app.contract;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DesktopSignFrameController {
    @GetMapping("/api/contracts/desktop-sign-frame")
    public ResponseEntity<String> page() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .header("X-Frame-Options", "DENY")
                .body(DesktopSignFramePage.html());
    }
}
