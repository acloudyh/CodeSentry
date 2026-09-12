package io.codesentry.rule.controller;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.rule.dto.RuleProfileView;
import io.codesentry.rule.service.RuleProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rule-profiles")
public class RuleProfileController {
    private final RuleProfileService service;

    public RuleProfileController(RuleProfileService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<RuleProfileView>> list() {
        return ApiResponse.success(service.list());
    }
}
