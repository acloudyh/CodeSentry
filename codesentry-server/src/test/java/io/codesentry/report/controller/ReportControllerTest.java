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
        when(service.exportFileName(eq(1L), anyString())).thenAnswer(invocation ->
                "一体化数据库信创改造-代码安全扫描." + invocation.getArgument(1));
        ReportController controller = new ReportController(service);
        ResponseEntity<byte[]> html = controller.exportHtml(1L);
        assertTrue(html.getHeaders().getContentDisposition().isInline());
        assertEquals("text/html;charset=UTF-8", html.getHeaders().getContentType().toString());
        assertTrue(html.getHeaders().getFirst("Content-Security-Policy").contains("sandbox"));
        assertEquals("no-store", html.getHeaders().getCacheControl());
        assertEquals("一体化数据库信创改造-代码安全扫描.html", html.getHeaders().getContentDisposition().getFilename());
        assertTrue(controller.exportDocx(1L).getHeaders().getContentDisposition().isAttachment());
        assertTrue(controller.exportPdf(1L).getHeaders().getContentDisposition().isAttachment());
        assertEquals("一体化数据库信创改造-代码安全扫描.docx", controller.exportDocx(1L).getHeaders().getContentDisposition().getFilename());
        assertEquals("一体化数据库信创改造-代码安全扫描.pdf", controller.exportPdf(1L).getHeaders().getContentDisposition().getFilename());
    }
}
