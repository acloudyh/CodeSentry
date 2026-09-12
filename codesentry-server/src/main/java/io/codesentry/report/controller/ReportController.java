package io.codesentry.report.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.report.dto.RemediationReportSummary;
import io.codesentry.report.service.RemediationReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final RemediationReportService service;

    public ReportController(RemediationReportService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<RemediationReportSummary>> recent(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(service.recent(limit));
    }

    @GetMapping(value = "/scan-runs/{runId}/html", produces = "text/html;charset=UTF-8")
    public ResponseEntity<byte[]> exportHtml(@PathVariable Long runId) {
        return download(service.exportHtml(runId), "codesentry-report-" + runId + ".html",
                new MediaType("text", "html", StandardCharsets.UTF_8));
    }

    @GetMapping(value = "/scan-runs/{runId}/docx",
            produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    public ResponseEntity<byte[]> exportDocx(@PathVariable Long runId) {
        return download(service.exportDocx(runId), "codesentry-report-" + runId + ".docx",
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @GetMapping(value = "/scan-runs/{runId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long runId) {
        return download(service.exportPdf(runId), "codesentry-report-" + runId + ".pdf",
                MediaType.APPLICATION_PDF);
    }

    private ResponseEntity<byte[]> download(byte[] content, String fileName, MediaType mediaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());
        return ResponseEntity.ok().headers(headers).body(content);
    }
}
