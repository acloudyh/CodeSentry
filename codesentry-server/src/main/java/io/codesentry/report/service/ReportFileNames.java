package io.codesentry.report.service;

/** 单资产及项目汇总导出使用相同命名约定，类型信息仅在报告正文展示。 */
public final class ReportFileNames {
  private ReportFileNames() {}

  public static String securityScan(String projectName, String extension) {
    String safe =
        (projectName == null ? "项目" : projectName)
            .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
            .trim();
    return (safe.isEmpty() ? "项目" : safe) + "安全扫描." + extension;
  }
}
