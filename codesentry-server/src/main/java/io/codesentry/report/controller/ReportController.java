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

/** 提供报告分页、原问题采纳明细、异步联网补查及 HTML/Word/PDF 导出接口。 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final RemediationReportService service;
    @org.springframework.beans.factory.annotation.Autowired private io.codesentry.finding.service.RemediationRefreshService refresh;

    /** 提交原联网扫描的后台补查并立即返回任务状态，不等待网络查询结束。 */
    @org.springframework.web.bind.annotation.PostMapping("/scan-runs/{runId}/verify-online")
    public ApiResponse<io.codesentry.scan.model.ScanRun> verifyOnline(@PathVariable Long runId) { return ApiResponse.success(refresh.start(runId)); }


    /** 注入统一报告服务，三种导出共享原报告统计和采纳口径。 */
    public ReportController(RemediationReportService service) {
        this.service = service;
    }

    /** 兼容旧客户端的最近报告列表；新页面改用完整历史分页接口。 */
    @GetMapping
    public ApiResponse<List<RemediationReportSummary>> recent(@RequestParam(defaultValue = "100") int limit,@RequestParam(required=false) Long businessProjectId,@RequestParam(required=false) Long assetId) {
        return ApiResponse.success(service.recent(limit,businessProjectId,assetId));
    }

    /** 分页查询报告，项目和资产条件在分页前生效，total 返回全部匹配数量。 */
    @GetMapping("/page")
    public ApiResponse<io.codesentry.report.dto.ReportPage> page(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) Long businessProjectId,
            @RequestParam(required=false) Long assetId) {
        return ApiResponse.success(service.page(page,pageSize,businessProjectId,assetId));
    }

    /** 返回原报告采纳的问题编号，供台账对账和验收使用。 */
    @GetMapping("/scan-runs/{runId}/included-finding-ids")
    public ApiResponse<List<Long>> includedFindingIds(@PathVariable Long runId) {
        return ApiResponse.success(service.includedFindingIds(runId));
    }

    /** 返回未纳入报告的问题与排除原因，保留原始问题身份。 */
    @GetMapping("/scan-runs/{runId}/excluded-findings")
    public ApiResponse<List<io.codesentry.report.dto.ReportExclusion>> excludedFindings(@PathVariable Long runId) {
        return ApiResponse.success(service.excludedFindings(runId));
    }

    /** 返回只读在线报告，并通过响应安全策略隔离不可信源码片段。 */
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
    /** 下载保留中文字体与原始问题来源的 Word 报告。 */
    public ResponseEntity<byte[]> exportDocx(@PathVariable Long runId) {
        return download(service.exportDocx(runId), service.exportFileName(runId, "docx"),
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    /** 下载使用统一采纳与统计口径生成的 PDF 报告。 */
    @GetMapping(value = "/scan-runs/{runId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long runId) {
        return download(service.exportPdf(runId), service.exportFileName(runId, "pdf"),
                MediaType.APPLICATION_PDF);
    }

    /** 设置附件类型与 UTF-8 下载文件名，统一二进制导出响应。 */
    private ResponseEntity<byte[]> download(byte[] content, String fileName, MediaType mediaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(content);
    }
}
