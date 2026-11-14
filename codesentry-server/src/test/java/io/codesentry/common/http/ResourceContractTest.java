package io.codesentry.common.http;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.codesentry.ai.controller.AiToolConfigurationController;
import io.codesentry.ai.dto.AiToolConfigurationView;
import io.codesentry.ai.service.AiToolConfigurationService;
import io.codesentry.business.*;
import io.codesentry.common.error.GlobalExceptionHandler;
import io.codesentry.git.credential.*;
import io.codesentry.report.controller.ReportArchiveController;
import io.codesentry.report.service.ReportArchiveService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 核对资源创建、删除和归档协议，防止成功状态与资源位置在 Controller 层退化。 */
class ResourceContractTest {
  @Test
  void createAndDeleteResourcesUsePreciseStatusAndNoBody() throws Exception {
    BusinessProjectService projects = mock(BusinessProjectService.class);
    BusinessProject project = new BusinessProject();
    project.id = 2L;
    when(projects.save(isNull(), anyString(), any())).thenReturn(project);
    GitCredentialService credentials = mock(GitCredentialService.class);
    when(credentials.save(isNull(), any())).thenReturn(Map.of("id", 3L));
    AiToolConfigurationService tools = mock(AiToolConfigurationService.class);
    AiToolConfigurationView tool = mock(AiToolConfigurationView.class);
    when(tool.getId()).thenReturn(4L);
    when(tools.create(any())).thenReturn(tool);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new BusinessProjectController(
                    projects, mock(ProjectLedgerService.class), mock(ProjectReportService.class)),
                new GitCredentialController(credentials),
                new AiToolConfigurationController(tools))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    mvc.perform(
            post("/api/v1/business-projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"test\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/business-projects/2"));
    mvc.perform(
            post("/api/v1/settings/git-credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"test\",\"serverUrl\":\"https://git.example\",\"username\":\"test\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/settings/git-credentials/3"));
    mvc.perform(
            post("/api/v1/ai-tools")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"test\",\"provider\":\"test\",\"baseUrl\":\"https://model.example\",\"model\":\"test\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/ai-tools/4"));
    for (String path :
        new String[] {
          "/api/v1/business-projects/2", "/api/v1/settings/git-credentials/3", "/api/v1/ai-tools/4"
        })
      mvc.perform(delete(path)).andExpect(status().isNoContent()).andExpect(content().string(""));
  }

  @Test
  void missingIdempotencyKeyIs400InsteadOfUnexpected500() throws Exception {
    ReportArchiveService archives = mock(ReportArchiveService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new ReportArchiveController(archives))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    mvc.perform(
            post("/api/v2/report-archives")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scanRunId\":2}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    verifyNoInteractions(archives);
  }

  @Test
  void zipSubmissionReturnsTaskLocationWithoutWaitingForRendering() throws Exception {
    ProjectReportPackageService packages = mock(ProjectReportPackageService.class);
    ProjectReportPackageService.Job job = new ProjectReportPackageService.Job();
    job.id = "test-job";
    when(packages.start(1L, 2L, "PDF", "request-key")).thenReturn(job);
    var mvc = MockMvcBuilders.standaloneSetup(new ProjectReportPackageController(packages)).build();
    mvc.perform(
            post("/api/v1/business-projects/1/rounds/2/report-packages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"PDF\",\"requestKey\":\"request-key\"}"))
        .andExpect(status().isAccepted())
        .andExpect(
            header().string("Location", "/api/v1/business-projects/1/report-packages/test-job"));
  }
}
