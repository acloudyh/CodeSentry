package io.codesentry.report.controller;

import io.codesentry.report.service.RemediationReportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportControllerTest {
    @Test void htmlIsInlineAndSandboxedWhileDocumentsRemainDownloads() {
        RemediationReportService service = mock(RemediationReportService.class);
        when(service.exportHtml(1L)).thenReturn("<html>报告</html>".getBytes(StandardCharsets.UTF_8));
        when(service.exportDocx(1L)).thenReturn(new byte[]{1});
        when(service.exportPdf(1L)).thenReturn(new byte[]{2});
        ReportController controller = new ReportController(service);
        ResponseEntity<byte[]> html = controller.exportHtml(1L);
        assertTrue(html.getHeaders().getContentDisposition().isInline());
        assertEquals("text/html;charset=UTF-8", html.getHeaders().getContentType().toString());
        assertTrue(html.getHeaders().getFirst("Content-Security-Policy").contains("sandbox"));
        assertEquals("no-store", html.getHeaders().getCacheControl());
        assertTrue(controller.exportDocx(1L).getHeaders().getContentDisposition().isAttachment());
        assertTrue(controller.exportPdf(1L).getHeaders().getContentDisposition().isAttachment());
    }
}
