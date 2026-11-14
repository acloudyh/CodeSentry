package io.codesentry.report.controller;

import io.codesentry.report.dto.ArchiveRequest;
import io.codesentry.report.service.ReportArchiveService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** v2 显式归档接口；v1 GET 保持原 URL，但不再承担冻结内容的写入职责。 */
@RestController
@RequestMapping("/api/v2/report-archives")
public class ReportArchiveController {
  private final ReportArchiveService archives;

  public ReportArchiveController(ReportArchiveService archives) {
    this.archives = archives;
  }

  @PostMapping
  public ResponseEntity<Receipt> create(
      @Valid @RequestBody ArchiveRequest request, @RequestHeader("Idempotency-Key") String key) {
    String url = archives.archive(request, key);
    return ResponseEntity.created(URI.create(url)).body(new Receipt(url));
  }

  /** 公开传输模型只返回只读资源位置，不暴露内部表结构。 */
  public static final class Receipt {
    public final String reportUrl;

    public Receipt(String reportUrl) {
      this.reportUrl = reportUrl;
    }
  }
}
