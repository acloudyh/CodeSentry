package io.codesentry.scan.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.service.JarUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/scan-runs")
public class JarUploadController {
  private final JarUploadService service;

  public JarUploadController(JarUploadService service) {
    this.service = service;
  }

  @PostMapping(value = "/jar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiResponse<ScanRun> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) Integer javaVersion) {
    return ApiResponse.success(service.upload(file, projectName, javaVersion));
  }
}
