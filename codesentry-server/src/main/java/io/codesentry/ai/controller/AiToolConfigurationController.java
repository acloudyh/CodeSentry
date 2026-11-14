package io.codesentry.ai.controller;

import io.codesentry.ai.dto.AiToolConfigurationRequest;
import io.codesentry.ai.dto.AiToolConfigurationView;
import io.codesentry.ai.service.AiToolConfigurationService;
import io.codesentry.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-tools")
public class AiToolConfigurationController {
  private final AiToolConfigurationService service;

  public AiToolConfigurationController(AiToolConfigurationService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<List<AiToolConfigurationView>> list() {
    return ApiResponse.success(service.list());
  }

  @GetMapping("/{id}")
  public ApiResponse<AiToolConfigurationView> get(@PathVariable Long id) {
    return ApiResponse.success(service.requireView(id));
  }

  @PostMapping
  public org.springframework.http.ResponseEntity<ApiResponse<AiToolConfigurationView>> create(
      @Valid @RequestBody AiToolConfigurationRequest request) {
    AiToolConfigurationView created = service.create(request);
    return org.springframework.http.ResponseEntity.created(
            java.net.URI.create("/api/v1/ai-tools/" + created.getId()))
        .body(ApiResponse.success(created));
  }

  @PutMapping("/{id}")
  public ApiResponse<AiToolConfigurationView> update(
      @PathVariable Long id, @Valid @RequestBody AiToolConfigurationRequest request) {
    return ApiResponse.success(service.update(id, request));
  }

  @DeleteMapping("/{id}")
  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    service.delete(id);
  }
}
