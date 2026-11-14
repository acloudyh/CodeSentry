package io.codesentry.project.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.project.dto.BranchView;
import io.codesentry.project.dto.CreateProjectRequest;
import io.codesentry.project.model.ScanProject;
import io.codesentry.project.service.ScanProjectService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ScanProjectController {

  private final ScanProjectService service;

  public ScanProjectController(ScanProjectService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<List<ScanProject>> findAll() {
    return ApiResponse.success(service.findAll());
  }

  @PostMapping("/git/validate")
  public ApiResponse<io.codesentry.project.dto.GitValidationResult> validateGit(
      @Valid @RequestBody io.codesentry.project.dto.GitValidationRequest request) {
    return ApiResponse.success(service.validateGit(request));
  }

  @GetMapping("/{id}")
  public ApiResponse<ScanProject> findOne(@PathVariable Long id) {
    return ApiResponse.success(service.requireById(id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<ScanProject> create(@Valid @RequestBody CreateProjectRequest request) {
    return ApiResponse.success(service.create(request));
  }

  @GetMapping("/{id}/branches")
  public ApiResponse<List<BranchView>> branches(
      @PathVariable Long id, @RequestParam(defaultValue = "false") boolean refresh) {
    return ApiResponse.success(service.listBranches(id, refresh));
  }

  @PostMapping("/{id}/sync")
  public ApiResponse<ScanProject> sync(@PathVariable Long id) {
    return ApiResponse.success(service.sync(id));
  }

  @PutMapping("/{id}")
  public ApiResponse<ScanProject> update(
      @PathVariable Long id, @Valid @RequestBody CreateProjectRequest request) {
    return ApiResponse.success(service.update(id, request));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    service.delete(id);
  }
}
