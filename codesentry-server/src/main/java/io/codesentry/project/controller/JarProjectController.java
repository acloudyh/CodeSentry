package io.codesentry.project.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.project.model.ScanProject;
import io.codesentry.project.service.JarProjectService;
import io.codesentry.scan.service.JarUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects")
public class JarProjectController {
  private final JarProjectService service;
  private final JarUploadService uploads;

  public JarProjectController(JarProjectService service, JarUploadService uploads) {
    this.service = service;
    this.uploads = uploads;
  }

  @PostMapping(value = "/jar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<ScanProject> create(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) Integer javaVersion) {
    return ApiResponse.success(uploads.register(file, projectName, javaVersion));
  }

  @PutMapping(value = "/{id}/jar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<ScanProject> update(
      @PathVariable Long id,
      @RequestParam String projectName,
      @RequestParam(required = false) Integer javaVersion,
      @RequestParam(required = false) MultipartFile file) {
    return ApiResponse.success(service.update(id, projectName, javaVersion, file));
  }
}
