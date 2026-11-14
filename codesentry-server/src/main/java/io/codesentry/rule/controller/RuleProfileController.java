package io.codesentry.rule.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.rule.dto.RuleLibraryStatus;
import io.codesentry.rule.dto.RuleProfileView;
import io.codesentry.rule.service.CommunityRuleLibraryService;
import io.codesentry.rule.service.RuleProfileService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rule-profiles")
public class RuleProfileController {
  private final RuleProfileService service;

  private final CommunityRuleLibraryService library;

  public RuleProfileController(RuleProfileService service, CommunityRuleLibraryService library) {
    this.service = service;
    this.library = library;
  }

  @GetMapping("/library")
  public ApiResponse<RuleLibraryStatus> library() {
    return ApiResponse.success(library.status());
  }

  @PostMapping("/library/update")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiResponse<RuleLibraryStatus> update() {
    return ApiResponse.success(library.requestUpdate());
  }

  @GetMapping
  public ApiResponse<List<RuleProfileView>> list() {
    return ApiResponse.success(service.list());
  }
}
