package io.codesentry.scan.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.scan.dto.CreateScanRunRequest;
import io.codesentry.scan.dto.ScanRunDetail;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.service.ScanRunService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/scan-runs")
public class ScanRunController {

    @PostMapping("/{id}/cancel")
    public ApiResponse<ScanRun> cancel(@PathVariable Long id) {
        return ApiResponse.success(service.cancel(id));
    }

    private final ScanRunService service;

    public ScanRunController(ScanRunService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ScanRun> start(@Valid @RequestBody CreateScanRunRequest request) {
        return ApiResponse.success(service.start(request));
    }

    @GetMapping
    public ApiResponse<List<ScanRun>> recent(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.recent(limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<ScanRun> findOne(@PathVariable Long id) {
        return ApiResponse.success(service.requireById(id));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<ScanRunDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }
}
