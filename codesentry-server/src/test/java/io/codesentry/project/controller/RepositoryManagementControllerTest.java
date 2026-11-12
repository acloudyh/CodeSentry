package io.codesentry.project.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.GlobalExceptionHandler;
import io.codesentry.project.model.*;
import io.codesentry.project.service.ScanProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RepositoryManagementControllerTest {
    private final ScanProjectService service=mock(ScanProjectService.class);
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new ScanProjectController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
    private final ObjectMapper json=new ObjectMapper();
    @Test void validNodeVersionIsAcceptedAndCredentialCiphertextIsNeverReturned() throws Exception {
        ScanProject project=new ScanProject();project.setId(1L);project.setApplicationType(ApplicationType.FRONTEND);project.setTargetNodeVersion("20.19.0");project.setGitUsername("user");project.setGitPasswordCiphertext("encrypted-fixture-only");when(service.create(any())).thenReturn(project);
        Map<String,Object> request=Map.of("projectName","frontend","sourceType","GIT","applicationType","FRONTEND","targetNodeVersion","20.19.0","remoteUrl","https://example.invalid/repo.git","gitUsername","user","gitPassword","fixture-only-password");
        mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andExpect(status().isCreated()).andExpect(jsonPath("$.data.targetNodeVersion").value("20.19.0")).andExpect(jsonPath("$.data.gitCredentialConfigured").value(true)).andExpect(jsonPath("$.data.gitPasswordCiphertext").doesNotExist()).andExpect(jsonPath("$.data.gitPassword").doesNotExist());
    }
    @Test void invalidRuntimeOrMultilineCredentialIsRejectedBeforeRepositoryAccess() throws Exception {
        Map<String,Object> request=new HashMap<>(Map.of("projectName","frontend","sourceType","GIT","applicationType","FRONTEND","targetNodeVersion","invalid-version"));
        mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andExpect(status().isBadRequest());
        request.put("targetNodeVersion","22");request.put("gitPassword","fixture\ninvalid");
        mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void syncIsAnExplicitPostOperation() throws Exception {
        ScanProject project=new ScanProject();project.setId(1L);project.setLastSyncStatus("COMPLETED");when(service.sync(1L)).thenReturn(project);
        mvc.perform(post("/api/v1/projects/1/sync")).andExpect(status().isOk()).andExpect(jsonPath("$.data.lastSyncStatus").value("COMPLETED"));verify(service).sync(1L);
    }
    @Test void gitProbeDoesNotRequireProjectNameAndReturnsOnlySafeResultFields() throws Exception {
        when(service.validateGit(any())).thenReturn(new io.codesentry.project.dto.GitValidationResult(true,"SUCCESS","仓库可读","main",List.of("main")));
        mvc.perform(post("/api/v1/projects/git/validate").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("remoteUrl","https://example.invalid/repo.git","gitUsername","user","gitPassword","fixture-only-password"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.valid").value(true)).andExpect(jsonPath("$.data.defaultBranch").value("main")).andExpect(jsonPath("$.data.gitPassword").doesNotExist());verify(service,never()).create(any());
    }

}
