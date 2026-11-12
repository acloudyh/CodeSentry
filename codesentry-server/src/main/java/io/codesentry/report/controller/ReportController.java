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
    @org.springframework.beans.factory.annotation.Autowired private io.codesentry.finding.service.RemediationRefreshService refresh;

    @org.springframework.web.bind.annotation.PostMapping("/scan-runs/{runId}/verify-online")
    public ApiResponse<io.codesentry.scan.model.ScanRun> verifyOnline(@PathVariable Long runId) { return ApiResponse.success(refresh.start(runId)); }


    public ReportController(RemediationReportService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<RemediationReportSummary>> recent(@RequestParam(defaultValue = "100") int limit,@RequestParam(required=false) Long businessProjectId,@RequestParam(required=false) Long assetId) {
        return ApiResponse.success(service.recent(limit,businessProjectId,assetId));
    }

    @GetMapping("/scan-runs/{runId}/included-finding-ids")
    public ApiResponse<List<Long>> includedFindingIds(@PathVariable Long runId) {
        return ApiResponse.success(service.includedFindingIds(runId));
    }

    @GetMapping("/scan-runs/{runId}/excluded-findings")
    public ApiResponse<List<io.codesentry.report.dto.ReportExclusion>> excludedFindings(@PathVariable Long runId) {
        return ApiResponse.success(service.excludedFindings(runId));
    }

    @GetMapping(value = "/scan-runs/{runId}/html", produces = "text/html;charset=UTF-8")
    public ResponseEntity<byte[]> exportHtml(@PathVariable Long runId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "html", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.inline().filename(service.exportFileName(runId, "html"), StandardCharsets.UTF_8).build());
        // Source snippets are untrusted input; the report is view-only even if escaping regresses.
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(service.exportHtml(runId));
    }

    @GetMapping(value = "/scan-runs/{runId}/docx",
            produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    public ResponseEntity<byte[]> exportDocx(@PathVariable Long runId) {
        return download(service.exportDocx(runId), service.exportFileName(runId, "docx"),
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @GetMapping(value = "/scan-runs/{runId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long runId) {
        return download(service.exportPdf(runId), service.exportFileName(runId, "pdf"),
                MediaType.APPLICATION_PDF);
    }

    private ResponseEntity<byte[]> download(byte[] content, String fileName, MediaType mediaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(content);
    }
}
