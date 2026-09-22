package com.tradepass.framework.web.core.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProbeController {

    @GetMapping("/tcb_probe")
    public ResponseEntity<Void> probe() {
        return ResponseEntity.ok().build();
    }
}
