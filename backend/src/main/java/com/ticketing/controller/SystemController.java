package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.common.SystemStatusResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return ApiResponse.success(new SystemStatusResponse("票务交易平台", "RUNNING"));
    }
}
