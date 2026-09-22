package com.tradepass.module.identity.controller.app.notice;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.module.identity.service.notice.MemberRemovalNoticeService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/me/membership-notices")
public class MemberRemovalNoticeController {
    private final MemberRemovalNoticeService service;
    public MemberRemovalNoticeController(MemberRemovalNoticeService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<MemberRemovalNoticeService.Notice>> pending() {
        return ApiResponse.ok(service.pending());
    }

    public record Acknowledgement(List<String> ids) {}

    @PostMapping("/acknowledge")
    public ApiResponse<Void> acknowledge(@RequestBody Acknowledgement request) {
        service.acknowledge(request.ids());
        return ApiResponse.ok(null);
    }
}
