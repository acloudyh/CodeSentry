package io.codesentry.common.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.common.error.ApiErrorController;
import io.codesentry.common.error.GlobalExceptionHandler;
import io.codesentry.project.model.ScanProject;
import io.codesentry.scan.model.ScanRun;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

/** 对外资源位置与容器兜底错误的行为测试，不依赖扫描器或生产数据库。 */
class ResourceLocationAdviceTest {
  @Test
  void createdAndAcceptedResponsesHaveLocations() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(new Endpoints())
            .setControllerAdvice(new ResourceLocationAdvice(), new GlobalExceptionHandler())
            .build();
    mvc.perform(post("/created"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/projects/2"));
    mvc.perform(post("/accepted"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/api/v1/scan-runs/3"));
    mvc.perform(get("/missing?count=bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    mvc.perform(post("/created").contentType("image/png")).andExpect(status().isCreated());
  }

  @Test
  void containerErrorsNeverEchoExceptionAttributes() {
    ApiErrorController controller = new ApiErrorController();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(
        RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("private-value"));
    assertEquals(500, controller.error(request).getStatusCode().value());
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
    assertEquals(404, controller.error(request).getStatusCode().value());
    assertFalse(controller.error(request).getBody().detail.contains("private-value"));
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 200);
    assertEquals(500, controller.error(request).getStatusCode().value());
  }

  @RestController
  static class Endpoints {
    @PostMapping("/created")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ScanProject> create() {
      ScanProject project = new ScanProject();
      project.setId(2L);
      return ApiResponse.success(project);
    }

    @PostMapping("/accepted")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<ScanRun> start() {
      ScanRun run = new ScanRun();
      run.setId(3L);
      return ApiResponse.success(run);
    }

    @GetMapping("/missing")
    ApiResponse<Integer> missing(@RequestParam int count) {
      return ApiResponse.success(count);
    }
  }
}
