package io.codesentry.business;

import io.codesentry.common.api.ApiResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** 项目 ZIP 附加导出接口，不替换单资产及项目汇总报告。 */
@RestController
@RequestMapping("/api/v1/business-projects/{projectId}")
public class ProjectReportPackageController {
    private final ProjectReportPackageService packages;
    public ProjectReportPackageController(ProjectReportPackageService packages) { this.packages=packages; }
    public static class Request { @NotBlank public String format; @NotBlank @Size(max=80) public String requestKey; }
    @PostMapping("/rounds/{roundId}/report-packages")
    public ApiResponse<ProjectReportPackageService.Job> start(@PathVariable Long projectId,@PathVariable Long roundId,@Valid @RequestBody Request body) {
        return ApiResponse.success(packages.start(projectId,roundId,body.format,body.requestKey));
    }
    @GetMapping("/rounds/{roundId}/report-packages/latest")
    public ApiResponse<ProjectReportPackageService.Job> latest(@PathVariable Long projectId,@PathVariable Long roundId,@RequestParam String format) {
        return ApiResponse.success(packages.latest(projectId,roundId,format));
    }
    @GetMapping("/report-packages/{jobId}")
    public ApiResponse<ProjectReportPackageService.Job> job(@PathVariable Long projectId,@PathVariable String jobId) {
        return ApiResponse.success(packages.require(projectId,jobId));
    }
    /** 文件流式发送，下载不把整个 ZIP 再载入 JVM 堆。 */
    @GetMapping("/report-packages/{jobId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long projectId,@PathVariable String jobId) throws java.io.IOException {
        ProjectReportPackageService.Job job=packages.require(projectId,jobId);Path file=packages.download(projectId,jobId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).contentLength(Files.size(file))
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(job.fileName,StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }
}
