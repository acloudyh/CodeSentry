package io.codesentry.business;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.project.model.ScanProject;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/business-projects")
public class BusinessProjectController {
    @org.springframework.beans.factory.annotation.Autowired private io.codesentry.finding.service.QualityChecklistService quality;
    @GetMapping("/{id}/quality.csv") public ResponseEntity<byte[]> quality(@PathVariable Long id,@RequestParam(required=false) Long roundId,@RequestParam(required=false) Long assetId){return ResponseEntity.ok().header("Content-Disposition","attachment; filename=quality-checklist.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(quality.project(id,roundId,assetId));}
    private final BusinessProjectService projects;
    private final ProjectLedgerService ledger;
    private final ProjectReportService reports;
    public BusinessProjectController(BusinessProjectService projects,ProjectLedgerService ledger,ProjectReportService reports) { this.projects=projects;this.ledger=ledger;this.reports=reports; }
    public static class ProjectRequest { @NotBlank @Size(max=128) public String name; @Size(max=2000) public String description; }
    public static class RetryRequest { @NotBlank @Size(max=80) public String requestKey; }
    @GetMapping public ApiResponse<List<BusinessProject>> list() { return ApiResponse.success(projects.list()); }
    @PostMapping public ApiResponse<BusinessProject> create(@Valid @RequestBody ProjectRequest body) { return ApiResponse.success(projects.save(null,body.name,body.description)); }
    @GetMapping("/{id}") public ApiResponse<BusinessProject> get(@PathVariable Long id) { return ApiResponse.success(projects.require(id)); }
    @PutMapping("/{id}") public ApiResponse<BusinessProject> update(@PathVariable Long id,@Valid @RequestBody ProjectRequest body) { return ApiResponse.success(projects.save(id,body.name,body.description)); }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable Long id) { projects.delete(id);return ApiResponse.success(null); }
    @GetMapping("/{id}/assets") public ApiResponse<List<ScanProject>> assets(@PathVariable Long id) { return ApiResponse.success(projects.assets(id)); }
    @PutMapping("/{id}/assets/{assetId}") public ApiResponse<Void> attach(@PathVariable Long id,@PathVariable Long assetId) { projects.attach(id,assetId);return ApiResponse.success(null); }
    @DeleteMapping("/{id}/assets/{assetId}") public ApiResponse<Void> detach(@PathVariable Long id,@PathVariable Long assetId) { projects.detach(id,assetId);return ApiResponse.success(null); }
    @GetMapping("/{id}/rounds") public ApiResponse<Map<String,Object>> rounds(@PathVariable Long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return ApiResponse.success(projects.rounds(id,page,pageSize)); }
    @PostMapping("/{id}/rounds") public ApiResponse<ProjectScanRound> start(@PathVariable Long id,@Valid @RequestBody ProjectScanRequest body) { return ApiResponse.success(projects.start(id,body)); }
    @GetMapping("/{id}/rounds/{roundId}") public ApiResponse<ProjectScanRound> round(@PathVariable Long id,@PathVariable Long roundId) { return ApiResponse.success(projects.round(id,roundId)); }
    @PostMapping("/{id}/rounds/{roundId}/retry") public ApiResponse<ProjectScanRound> retry(@PathVariable Long id,@PathVariable Long roundId,@Valid @RequestBody RetryRequest body) { return ApiResponse.success(projects.retry(id,roundId,body.requestKey)); }
    @PostMapping("/{id}/rounds/{roundId}/cancel") public ApiResponse<ProjectScanRound> cancel(@PathVariable Long id,@PathVariable Long roundId) { return ApiResponse.success(projects.cancel(id,roundId)); }
    @GetMapping("/{id}/findings") public ApiResponse<Map<String,Object>> findings(@PathVariable Long id,@RequestParam(required=false) Long roundId,@RequestParam(required=false) Long assetId,@RequestParam(required=false) String severity,@RequestParam(required=false) String triage,@RequestParam(required=false) String reportState,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="ALL") String issueType) { return ApiResponse.success(ledger.page(id,roundId,assetId,severity,triage,reportState,keyword,page,pageSize,issueType)); }
    @GetMapping("/{id}/rounds/{roundId}/report/{format}") public ResponseEntity<byte[]> report(@PathVariable Long id,@PathVariable Long roundId,@PathVariable String format) {
        if(!List.of("html","docx","pdf").contains(format)) throw BusinessProjectService.bad("不支持的报告格式");
        if(roundId<0) {
            byte[] data=reports.assetHistoryReport(id,roundId,format);
            MediaType type="html".equals(format)?new MediaType("text","html",StandardCharsets.UTF_8):"pdf".equals(format)?MediaType.APPLICATION_PDF:MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            HttpHeaders headers=new HttpHeaders();headers.setContentType(type);headers.setCacheControl("no-store");headers.set("X-Content-Type-Options","nosniff");
            headers.setContentDisposition(("html".equals(format)?ContentDisposition.inline():ContentDisposition.attachment()).filename(reports.assetHistoryReportName(id,roundId,format),StandardCharsets.UTF_8).build());
            if("html".equals(format)) headers.set("Content-Security-Policy","sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:");
            return ResponseEntity.ok().headers(headers).body(data);
        }
        ProjectReportService.Snapshot snapshot=reports.archive(id,roundId);
        byte[] data="html".equals(format)?reports.html(snapshot):"pdf".equals(format)?reports.pdf(snapshot):reports.docx(snapshot);
        MediaType type="html".equals(format)?new MediaType("text","html",StandardCharsets.UTF_8):"pdf".equals(format)?MediaType.APPLICATION_PDF:MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        String name=snapshot.round.projectName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_")+"-项目整改报告-P"+roundId+"."+format;
        HttpHeaders headers=new HttpHeaders();headers.setContentType(type);headers.setCacheControl("no-store");headers.set("X-Content-Type-Options","nosniff");
        headers.setContentDisposition(("html".equals(format)?ContentDisposition.inline():ContentDisposition.attachment()).filename(name,StandardCharsets.UTF_8).build());
        if("html".equals(format)) headers.set("Content-Security-Policy","sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:");
        return ResponseEntity.ok().headers(headers).body(data);
    }
}
