package io.codesentry.git.credential;

import io.codesentry.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 系统设置中的 Git 账号维护接口，仅输出脱离秘密的元数据。 */
@RestController
@RequestMapping("/api/v1/settings/git-credentials")
public class GitCredentialController {
  private final GitCredentialService service;

  public GitCredentialController(GitCredentialService service) {
    this.service = service;
  }

  /** 返回可用于登记仓库的已维护账号。 */
  @GetMapping
  public ApiResponse<List<Map<String, Object>>> list() {
    return ApiResponse.success(service.list());
  }

  /** 加密保存一个共享账号。 */
  @PostMapping
  public org.springframework.http.ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @Valid @RequestBody GitCredentialRequest request) {
    Map<String, Object> created = service.save(null, request);
    return org.springframework.http.ResponseEntity.created(
            java.net.URI.create("/api/v1/settings/git-credentials/" + created.get("id")))
        .body(ApiResponse.success(created));
  }

  /** 更新账号或轮换密码；留空保持已保存密码。 */
  @PutMapping("/{id}")
  public ApiResponse<Map<String, Object>> update(
      @PathVariable Long id, @Valid @RequestBody GitCredentialRequest request) {
    return ApiResponse.success(service.save(id, request));
  }

  /** 仅允许删除不再被有效仓库使用的账号。 */
  @DeleteMapping("/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    service.delete(id);
  }
}
