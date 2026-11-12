package io.codesentry.business;

import io.codesentry.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

class BusinessProjectControllerTest {
    private final BusinessProjectService service=mock(BusinessProjectService.class);
    private final ProjectReportService reports=mock(ProjectReportService.class);
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new BusinessProjectController(service,mock(ProjectLedgerService.class),reports)).setControllerAdvice(new GlobalExceptionHandler()).build();
    @Test void rejectsEmptyProjectAndInvalidNestedScanAssetsBeforeSideEffects() throws Exception {
        mvc.perform(post("/api/v1/business-projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/business-projects/1/rounds").contentType(MediaType.APPLICATION_JSON).content("{\"requestKey\":\"a\",\"networkMode\":\"LOCAL\",\"assets\":[null]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/business-projects/1/rounds").contentType(MediaType.APPLICATION_JSON).content("{\"requestKey\":\"a\",\"networkMode\":\"UNKNOWN\",\"assets\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/business-projects/1/rounds").contentType(MediaType.APPLICATION_JSON).content("{\"requestKey\":\"a\",\"networkMode\":\"LOCAL\",\"assets\":[{\"assetId\":1}]}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service,reports);
    }
    @Test void unsupportedExportFormatDoesNotArchive() throws Exception {
        mvc.perform(get("/api/v1/business-projects/1/rounds/2/report/exe")).andExpect(status().isBadRequest());verifyNoInteractions(reports);
    }
}
