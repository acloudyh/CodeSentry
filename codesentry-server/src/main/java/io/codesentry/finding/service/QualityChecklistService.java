package io.codesentry.finding.service;

import io.codesentry.business.*;
import io.codesentry.finding.model.FindingView;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** 编码规范清单独立导出，绝不改写或删除扫描与审核记录。 */
@Service
public class QualityChecklistService {
  private final FindingPersistenceService findings;
  private final BusinessProjectService projects;

  public QualityChecklistService(
      FindingPersistenceService findings, BusinessProjectService projects) {
    this.findings = findings;
    this.projects = projects;
  }

  public byte[] asset(Long runId) {
    return build(List.of(runId));
  }

  public byte[] project(Long projectId, Long roundId, Long assetId) {
    projects.require(projectId);
    if (roundId == null) roundId = projects.latestRoundId(projectId);
    if (roundId == null) return build(List.of());
    List<Long> runs = new ArrayList<>();
    for (ProjectScanItem item : projects.round(projectId, roundId).items)
      if (item.runId != null
          && List.of("COMPLETED", "PARTIAL").contains(item.status)
          && (assetId == null || assetId.equals(item.assetId))) runs.add(item.runId);
    return build(runs);
  }

  private byte[] build(List<Long> runs) {
    StringBuilder csv = new StringBuilder("\uFEFF问题编号,资产编号,来源扫描,规则,原规则级别,问题,位置,规范整改建议,人工结论\r\n");
    for (Long run : runs)
      for (FindingView f : findings.findStoredByRunId(run))
        if ("QUALITY".equals(FindingClassification.type(f))) {
          List<String> cells =
              Arrays.asList(
                  "F-" + f.getId(),
                  String.valueOf(f.getProjectId()),
                  String.valueOf(run),
                  f.getRuleId(),
                  f.getSeverity(),
                  f.getTitle(),
                  f.getFilePath(),
                  FindingClassification.precheck(f).get("recommendation"),
                  f.getTriageStatus());
          csv.append(
                  cells.stream().map(this::cell).collect(java.util.stream.Collectors.joining(",")))
              .append("\r\n");
        }
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** 禁止文件名、标题等不可信文本被 Excel 当作公式执行。 */
  private String cell(String s) {
    s = s == null ? "" : s;
    String probe = s.replace("\uFEFF", "").stripLeading();
    if (!probe.isEmpty() && "=+-@".indexOf(probe.charAt(0)) >= 0) s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }
}
