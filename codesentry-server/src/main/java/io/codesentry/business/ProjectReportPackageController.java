package io.codesentry.business;

import io.codesentry.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 项目 ZIP 附加导出接口，不替换单资产及项目汇总报告。 */
@RestController
@RequestMapping("/api/v1/business-projects/{projectId}")
public class ProjectReportPackageController {
  private final ProjectReportPackageService packages;

  public ProjectReportPackageController(ProjectReportPackageService packages) {
    this.packages = packages;
  }

  public static class Request {
    @NotBlank public String format;

    @NotBlank
    @Size(max = 80)
    public String requestKey;
  }

  @PostMapping("/rounds/{roundId}/report-packages")
  public ResponseEntity<ApiResponse<ProjectReportPackageService.Job>> start(
      @PathVariable Long projectId, @PathVariable Long roundId, @Valid @RequestBody Request body) {
    ProjectReportPackageService.Job job =
        packages.start(projectId, roundId, body.format, body.requestKey);
    return ResponseEntity.accepted()
        .location(
            java.net.URI.create(
                "/api/v1/business-projects/" + projectId + "/report-packages/" + job.id))
        .body(ApiResponse.success(job));
  }

  @GetMapping("/rounds/{roundId}/report-packages/latest")
  public ApiResponse<ProjectReportPackageService.Job> latest(
      @PathVariable Long projectId, @PathVariable Long roundId, @RequestParam String format) {
    return ApiResponse.success(packages.latest(projectId, roundId, format));
  }

  @GetMapping("/report-packages/{jobId}")
  public ApiResponse<ProjectReportPackageService.Job> job(
      @PathVariable Long projectId, @PathVariable String jobId) {
    return ApiResponse.success(packages.require(projectId, jobId));
  }

  /** 文件流式发送，下载不把整个 ZIP 再载入 JVM 堆。 */
  @GetMapping("/report-packages/{jobId}/download")
  public ResponseEntity<Resource> download(@PathVariable Long projectId, @PathVariable String jobId)
      throws java.io.IOException {
    ProjectReportPackageService.Job job = packages.require(projectId, jobId);
    Path file = packages.download(projectId, jobId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .contentLength(Files.size(file))
        .cacheControl(CacheControl.noStore())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(job.fileName, StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(new FileSystemResource(file));
  }
}
