package io.codesentry.report.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.codesentry.common.error.BusinessException;
import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.model.AiReviewRecord;
import io.codesentry.ai.service.AiReviewService;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.DependencyFindingGrouping;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.project.model.ScanProject;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.report.dto.RemediationReportSummary;
import io.codesentry.report.dto.ReportExclusion;
import io.codesentry.finding.service.ReviewedFindingPolicy;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.service.ScanRunService;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RemediationReportService {
    private static final int REPORT_CODE_MAX_LINES = 30;
    private static final int REPORT_CODE_MAX_CHARS = 600;
    private static final String REPORT_CODE_TRUNCATED = "\n...（代码片段过长，报告中已截断）";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String WORD_BODY_FONT = "宋体";
    private static final String WORD_HEADING_FONT = "黑体";

    private final ScanRunService scanRunService;
    private final ScanProjectService projectService;
    private final FindingPersistenceService findingService;
    private final AiReviewService aiReviewService;
    private final ReportSnapshotStore snapshots;

    public RemediationReportService(ScanRunService scanRunService, ScanProjectService projectService,
                                    FindingPersistenceService findingService, AiReviewService aiReviewService, ReportSnapshotStore snapshots) {
        this.scanRunService = scanRunService;
        this.projectService = projectService;
        this.findingService = findingService;
        this.aiReviewService = aiReviewService;
        this.snapshots = snapshots;
    }

    public byte[] exportHtml(Long runId) {
        return buildHtml(load(runId, true)).getBytes(StandardCharsets.UTF_8);
    }

    public List<Long> includedFindingIds(Long runId) {
        return load(runId, false).findings.stream().map(FindingView::getId).collect(Collectors.toList());
    }

    public List<ReportExclusion> excludedFindings(Long runId) { return load(runId, false).exclusions; }

    public String exportFileName(Long runId, String extension) {
        ScanRun run = scanRunService.requireById(runId);
        ScanProject project = projectService.requireById(run.getProjectId());
        String projectName = project.getDisplayName();
        String safeName = projectName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        String projectType = project.getSourceType() == io.codesentry.project.model.ProjectSourceType.JAR_UPLOAD ? "jar包" : "代码";
        return safeName + projectType + "安全扫描." + extension;
    }

    public List<RemediationReportSummary> recent(int limit) {
        List<RemediationReportSummary> reports = new ArrayList<>();
        for (ScanRun run : scanRunService.recent(limit)) {
            if (run.getStatus() != io.codesentry.scan.model.ScanRunStatus.COMPLETED
                    && run.getStatus() != io.codesentry.scan.model.ScanRunStatus.PARTIAL) continue;
            ReportData data = load(run.getId(), false);
            reports.add(new RemediationReportSummary(run.getId(), run.getProjectId(), run.getScanMode(),
                    run.getStatus(), data.findings.size(), data.newFindingCount, run.getCreatedAt(),
                    data.ledgerFindingCount, groupFindings(data.findings).size(), data.notIncludedFindingCount));
        }
        reports.sort(Comparator.comparing(RemediationReportSummary::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return reports;
    }

    public byte[] exportPdf(Long runId) {
        ReportData data = load(runId, true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(buildHtml(data), null);
            builder.useFont(resolvePdfSongFont(), "CodeSentrySong");
            builder.useFont(resolvePdfHeiFont(), "CodeSentryHei");
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "PDF 报告生成失败：" + safeMessage(exception));
        }
    }

    public byte[] exportDocx(Long runId) {
        ReportData data = load(runId, true);
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            configureA4(document);
            addTitle(document, "CODESENTRY / REMEDIATION REPORT", 11, "78A921");
            addTitle(document, "代码安全扫描整改报告", 24, "14201B");
            addText(document, "报告编号 CSR-" + String.format("%06d", data.run.getId())
                    + "    项目 " + data.project.getDisplayName()
                    + "    生成时间 " + TIME.format(LocalDateTime.now()), 10, "66796E", false);

            XWPFTable summary = document.createTable(2, 5);
            summary.setWidth(9026);
            String[] labels = {"台账问题（条）", "纳入整改（条）", "合并整改（组）", "未纳入（条）", "扫描模式"};
            String[] values = {String.valueOf(data.ledgerFindingCount), String.valueOf(data.findings.size()),
                    String.valueOf(groupFindings(data.findings).size()), String.valueOf(data.notIncludedFindingCount), scanModeLabel(data.run)};
            for (int column = 0; column < labels.length; column++) {
                styleCell(summary.getRow(0).getCell(column), labels[column], true, "E9EFE9", 1805);
                styleCell(summary.getRow(1).getCell(column), values[column], false, "FFFFFF", 1805);
            }

            addHeading(document, "数量说明", 1);
            for (String[] row : countExplanationRows(data, groupFindings(data.findings).size())) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.setSpacingAfter(120);
                styleRun(paragraph.createRun(), row[0] + "　　", 10, "66796E", true);
                styleRun(paragraph.createRun(), row[1], 10, "14201B", false);
                if (!row[2].isEmpty()) {
                    XWPFRun detail = paragraph.createRun();
                    detail.addBreak();
                    styleRun(detail, row[2], 9, "66796E", false);
                }
            }

            addHeading(document, "扫描基线", 1);
            addText(document, "业务 Java 版本：" + javaVersionLabel(data.project), 10, "14201B", false);
            if (data.run.getNetworkMode() == io.codesentry.scan.model.NetworkMode.ONLINE)
                addText(document, "联网扫描：" + value(data.run.getRemediationMessage(), "联网修复依据尚未完成核实"), 10, "A46400", true);
            addText(document, "扫描分支：" + value(data.run.getScanRef(), "当前工作区")
                    + "    基线分支：" + value(data.run.getBaselineRef(), "无"), 10, "14201B", false);
            addText(document, "扫描提交：" + value(data.run.getScanCommit(), "-")
                    + "    merge-base：" + value(data.run.getMergeBaseCommit(), "-"), 9, "66796E", false);

            addHeading(document, "问题明细与整改建议", 1);
            if (data.findings.isEmpty()) {
                addText(document, "当前没有符合报告筛选条件的问题，不代表原始扫描未发现风险。", 10, "66796E", false);
            }
            int index = 1;
            for (ReportFindingGroup group : groupFindings(data.findings, data.project.getTargetJavaVersion())) {
                FindingView finding = group.primary();
                XWPFParagraph heading = document.createParagraph();
                heading.setSpacingBefore(220);
                heading.setSpacingAfter(80);
                styleRun(heading.createRun(), String.format("%02d  ", index++), 11, "78A921", true);
                styleRun(heading.createRun(), "[" + severityLabel(finding.getSeverity()) + "] ", 11,
                        severityColor(finding.getSeverity()), true);
                styleRun(heading.createRun(), group.title(), 11, "14201B", true);

                addText(document, "问题编号：" + group.findingIds(), 9, "66796E", false);
                addText(document, "扫描器 / 规则：" + finding.getScanner() + " / " + group.ruleIds(),
                        9, "66796E", false);
                addText(document, reportLocationLabel(finding), 9, "66796E", false);
                addText(document, "变化状态：" + group.resultLabels()
                        + "    人工结论：" + group.triageLabels(), 9, "66796E", false);
                if (group.isGrouped()) {
                    addText(document, "合并说明：同一依赖文件与组件版本的 " + group.members.size()
                            + " 个 CVE 合并展示；原始问题台账及统计仍按 CVE 保留。", 9, "66796E", false);
                    for (FindingView member : group.members) {
                        addText(document, "• " + value(member.getRuleId(), "unknown") + "  "
                                + value(member.getTitle(), "依赖漏洞") + "\n  利用条件：" + exploitCondition(member)
                                + "\n  复核证据：" + reviewEvidence(member), 9, "38483F", false);
                        addGroupedAiReviewToDocx(document, member, data.aiReviews.get(member.getId()));
                    }
                } else {
                    addText(document, "利用条件：" + exploitCondition(finding), 9, "38483F", false);
                    addText(document, "复核证据：" + reviewEvidence(finding), 9, "38483F", false);
                    AiReviewSummary aiReview = data.aiReviews.get(finding.getId());
                    if (aiReview != null) addAiReviewToDocx(document, aiReview);
                    if ("FALSE_POSITIVE".equals(finding.getTriageStatus())) {
                        addText(document, "误报原因：" + falsePositiveReason(finding), 9, "A46400", false);
                    }
                }
                if (hasText(finding.getCodeSnippet())) {
                    addCodeBlock(document, reportCodeSnippet(finding.getCodeSnippet()));
                }
                if (!group.isGrouped() && hasText(finding.getDescription()) && !finding.getDescription().equals(finding.getTitle())) {
                    addText(document, "问题说明：" + finding.getDescription(), 9, "38483F", false);
                }
                addText(document, "整改建议：", 9, "38483F", true);
                for (String paragraph : group.recommendation().split("\\R")) {
                    boolean coverage = paragraph.startsWith("覆盖情况：");
                    addText(document, paragraph, 9, coverage ? "A46400" : "38483F", coverage);
                }
            }

            if (!data.exclusions.isEmpty()) {
                addHeading(document, "未纳入整改的问题及原因", 1);
                addText(document, "以下记录用于台账对账，不计入待整改风险；原因按当前审核记录核对，已归档报告的纳入范围保持不变。", 9, "66796E", false);
                for (ReportExclusion item : data.exclusions) {
                    addText(document, "F-" + item.getFindingId() + " · " + item.getRuleId()
                            + " · " + item.getTitle(), 10, "14201B", true);
                    addText(document, "位置：" + value(item.getFilePath(), "未记录") + "\n未纳入原因：" + item.getReason(), 9, "38483F", false);
                }
            }

            List<ReportFindingGroup> sourceGroups = groupFindings(data.findings, data.project.getTargetJavaVersion()).stream()
                    .filter(group -> !group.sources().isEmpty()).collect(Collectors.toList());
            if (!sourceGroups.isEmpty()) {
                addHeading(document, "附录：整改版本依据", 1);
                addText(document, "修复记录仅针对所列漏洞；升级须核对 JDK 和依赖兼容性。官方补充规则核实日期：2026-11-10。正文推荐目标按项目 Java 版本筛选，与附录历史版本分别展示。", 9, "66796E", false);
                for (ReportFindingGroup group : sourceGroups) {
                    addHeading(document, group.findingIds() + " · " + group.title(), 2);
                    for (String record : io.codesentry.finding.service.DependencyRemediationAdvice.versionRecords(group.members)) {
                        addText(document, record, 9, "38483F", false);
                    }
                    for (Map.Entry<String, List<String>> source : group.sources().entrySet()) {
                        addText(document, String.join("、", source.getValue()) + "：" + source.getKey(), 9, "66796E", false);
                    }
                }
            }

            addText(document, "人工复核状态：" + data.reviewStatus()
                    + "报告保留人工已确认的风险，以及工具复核完成、通过可信度门槛且未判为误报的问题；人工判为误报的不纳入报告，可信度不代表漏洞可利用性。"
                    + "本报告由 CodeSentry 本地节点生成。",
                    8, "7B8B82", false);
            embedWordFonts(document);
            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Word 报告生成失败：" + safeMessage(exception));
        }
    }

    public List<FindingView> archiveFindings(Long runId) { return load(runId, true).findings; }

    private ReportData load(Long runId, boolean archive) {
        ScanRun run = scanRunService.requireById(runId);
        if (archive && run.getStatus() != io.codesentry.scan.model.ScanRunStatus.COMPLETED
                && run.getStatus() != io.codesentry.scan.model.ScanRunStatus.PARTIAL) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "扫描结束后才能归档和导出报告");
        }
        ScanProject project = projectService.requireById(run.getProjectId());
        List<FindingView> raw = findingService.findByRunId(runId);
        io.codesentry.finding.service.OnlineRemediationService.attach(run, raw);
        Map<Long, AiReviewSummary> aiReviews = new LinkedHashMap<>();
        aiReviewService.latestForRun(runId).forEach(summary -> aiReviews.put(summary.getFindingId(), summary));
        List<FindingView> visible = snapshots.read(runId);
        if (visible == null) {
            visible = io.codesentry.finding.service.ReviewedFindingPolicy.select(raw, aiReviews);
            if (archive) visible = snapshots.freeze(runId, visible);
        }
        io.codesentry.finding.service.OnlineRemediationService.attach(run, visible);
        return new ReportData(run, project, visible, aiReviews, raw);
    }


    private String buildHtml(ReportData data) {
        StringBuilder rows = new StringBuilder();
        int index = 1;
        List<ReportFindingGroup> groups = groupFindings(data.findings, data.project.getTargetJavaVersion());
        for (ReportFindingGroup group : groups) {
            FindingView finding = group.primary();
            rows.append("<tr><td>").append(index++).append("</td><td><span class=\"severity ")
                    .append(escape(finding.getSeverity().toLowerCase())).append("\">")
                    .append(severityLabel(finding.getSeverity())).append("</span></td><td><b>")
                    .append(escape(group.title())).append("</b><small>问题编号：").append(escape(group.findingIds())).append("</small><small>")
                    .append(escape(finding.getScanner())).append(" / ").append(escape(group.ruleIds()))
                    .append("</small>");
            if (group.isGrouped()) {
                rows.append("<ul class=\"vuln-list\">");
                for (FindingView member : group.members) {
                    rows.append("<li><b>").append(escape(value(member.getRuleId(), "unknown")))
                            .append("</b> ").append(escape(value(member.getTitle(), "依赖漏洞"))).append("</li>");
                }
                rows.append("</ul>");
            }
            if (hasText(finding.getCodeSnippet())) {
                rows.append("<pre>").append(escape(reportCodeSnippet(finding.getCodeSnippet()))).append("</pre>");
            }
            rows.append("</td><td><code>").append(escape(reportLocationValue(finding)))
                    .append("</code></td><td>")
                    .append(escape(group.resultLabels())).append("</td><td>")
                    .append(escape(group.triageLabels())).append("</td><td>");
            if (group.isGrouped()) appendGroupedEvidenceHtml(rows, group);
            else {
                rows.append("<b>利用条件：</b>").append(escape(exploitCondition(finding)))
                        .append("<br/><b>复核证据：</b>").append(escape(reviewEvidence(finding)));
                if ("FALSE_POSITIVE".equals(finding.getTriageStatus())) {
                    rows.append("<br/><b class=\"false-positive\">误报原因：</b>")
                            .append(escape(falsePositiveReason(finding)));
                }
            }
            rows.append("</td><td class=\"ai-cell\">");
            if (group.isGrouped()) appendGroupedAiReviewHtml(rows, group, data.aiReviews);
            else appendAiReviewHtml(rows, data.aiReviews.get(finding.getId()));
            rows.append("</td><td>")
                    .append(recommendationHtml(group.recommendation()))
                    .append("</td></tr>");
        }

        ScanRun run = data.run;
        ScanProject project = data.project;
        return "<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh-CN\"><head><meta charset=\"utf-8\" />" +
                "<title>CodeSentry 整改报告</title><style>@page{size:A4 landscape;margin:14mm}*{box-sizing:border-box}" +
                "body{font-family:'CodeSentrySong','SimSun',serif;margin:0;color:#14201b;background:#f4f7f3;font-size:12px}" +
                ".cover{padding:32px 38px;background:#0b1714;color:#eef4ec;border-bottom:6px solid #adf33f}.brand{letter-spacing:.12em;color:#adf33f;font-weight:800}" +
                "h1{margin:14px 0 8px;font-size:28px}.meta{color:#aec0b6}.wrap{padding:24px 38px 0}.brand,h1,h2,th,b{font-family:'CodeSentryHei','SimHei',sans-serif}.summary-table{width:100%;margin-bottom:22px;border-collapse:separate;border-spacing:10px 0}" +
                ".summary-table td{width:20%;background:white;border:1px solid #d9e2dc;padding:14px}.summary-table span{color:#66796e;font-size:11px}.summary-table b{display:block;font-size:24px;margin-top:7px}" +
                ".card{background:white;border:1px solid #d9e2dc;padding:16px;margin-bottom:20px;page-break-inside:avoid}.data{width:100%;border-collapse:collapse;background:white;font-size:10px;table-layout:fixed}" +
                ".count-card{padding:24px 26px}.count-card>b{font-size:16px}.count-lines{width:100%;margin-top:17px;border-collapse:collapse;font-size:14px;line-height:1.7}" +
                ".count-lines th,.count-lines td{padding:11px 0;border-bottom:1px solid #edf1ed;text-align:left;vertical-align:top}.count-lines th{width:106px;padding-right:14px;color:#66796e;font-weight:400}" +
                ".count-lines tr:last-child th,.count-lines tr:last-child td{border-bottom:0}.count-lines strong{font-family:'CodeSentryHei','SimHei',sans-serif;font-weight:500}.count-note{display:block;color:#66796e;font-size:12px;margin-top:3px}" +
                ".count-card{page-break-inside:avoid}" +
                ".data th,.data td{padding:9px 7px;border:1px solid #dfe6e1;text-align:left;vertical-align:top;word-wrap:break-word}.data th{background:#e9efe9;color:#44574c}" +
                "small{display:block;color:#718077;margin-top:5px}code{word-break:break-all}pre{white-space:pre-wrap;word-wrap:break-word;margin:8px 0 0;padding:8px;background:#f0f3ef;border-left:3px solid #78a921;font:8px/1.45 monospace}.vuln-list{margin:8px 0 0;padding-left:17px}.vuln-list li{margin:4px 0}.group-review{margin:0 0 8px;padding:0 0 8px;border-bottom:1px solid #eadfca}" +
                ".severity{font-weight:700}.critical,.high{color:#c83232}.medium{color:#a46400}.low,.info{color:#167c6d}.false-positive,.ai-review,.coverage{color:#a46400}.ai-cell{background:#fffaf0}.ai-cell b{display:block;margin-bottom:3px}.ai-cell small{margin:2px 0 7px;line-height:1.45}.foot{margin-top:18px;color:#718077;font-size:9px}" +
                "tr{page-break-inside:avoid}</style></head><body><header class=\"cover\"><div class=\"brand\">CODESENTRY / REMEDIATION REPORT</div>" +
                "<h1>代码安全扫描整改报告</h1><div class=\"meta\">报告编号 CSR-" + String.format("%06d", run.getId()) +
                " · 项目 " + escape(project.getDisplayName()) + " · 生成时间 " + TIME.format(LocalDateTime.now()) + "</div></header><main class=\"wrap\">" +
                "<table class=\"summary-table\"><tr>" + summaryCell("台账问题（条）", String.valueOf(data.ledgerFindingCount)) +
                summaryCell("纳入整改（条）", String.valueOf(data.findings.size())) + summaryCell("合并整改（组）", String.valueOf(groups.size())) + summaryCell("未纳入（条）", String.valueOf(data.notIncludedFindingCount)) +
                summaryCell("扫描模式", scanModeLabel(run)) +
                "</tr></table>" + countExplanationHtml(data, groups.size()) +
                "<section class=\"card\"><b>扫描基线</b><p>业务 Java 版本：" + javaVersionLabel(project) + "</p>"
                + (run.getNetworkMode() == io.codesentry.scan.model.NetworkMode.ONLINE
                    ? "<p class=\"ai-review\">联网扫描：" + escape(value(run.getRemediationMessage(), "联网修复依据尚未完成核实")) + "</p>" : "")
                + "<p>扫描分支：" +
                escape(value(run.getScanRef(), "当前工作区")) + "；基线分支：" + escape(value(run.getBaselineRef(), "无")) +
                "；扫描提交：" + escape(value(run.getScanCommit(), "-")) + "；merge-base：" + escape(value(run.getMergeBaseCommit(), "-")) +
                "</p></section>" + (groups.size() < data.findings.size() ? "<p class=\"foot\">合并说明：同一依赖文件与组件版本的多个 CVE 在明细中合并展示；统计仍按 CVE 数量计算。</p>" : "") +
                "<p class=\"foot\">人工复核状态：" + escape(data.reviewStatus()) +
                "报告保留人工已确认的风险，以及工具复核完成、通过可信度门槛且未判为误报的问题；人工判为误报的不纳入报告，可信度不代表漏洞可利用性。" +
                "本报告由 CodeSentry 本地节点生成。</p><h2>问题明细与整改建议</h2><table class=\"data\"><thead><tr><th style=\"width:3%\">#</th><th style=\"width:6%\">等级</th>" +
                "<th style=\"width:18%\">问题 / 规则 / 代码片段</th><th style=\"width:14%\">位置</th><th style=\"width:6%\">变化</th><th style=\"width:8%\">人工结论</th><th style=\"width:15%\">利用条件 / 复核证据</th><th style=\"width:16%\">工具复核</th><th style=\"width:14%\">整改建议</th>" +
                "</tr></thead><tbody>" + (rows.length() == 0 ? "<tr><td colspan=\"9\">当前没有符合报告筛选条件的问题，不代表原始扫描未发现风险。</td></tr>" : rows) +
                "</tbody></table>" + exclusionsHtml(data) + buildReferencesHtml(data.findings) + "</main></body></html>";
    }


    private String[][] countExplanationRows(ReportData data, int groups) {
        return new String[][] {
                {"台账对账", data.ledgerFindingCount + " 条台账问题 = " + data.findings.size()
                        + " 条纳入整改 + " + data.notIncludedFindingCount + " 条未纳入", ""},
                {"合并展示", data.findings.size() + " 条纳入问题，合并为 " + groups + " 组整改项。",
                        "同一依赖文件、同一组件版本的多个漏洞合并展示，组内保留问题编号与 CVE。"},
                {"未纳入项", data.notIncludedFindingCount + " 条未纳入正文。",
                        data.notIncludedFindingCount == 0 ? "本批次台账问题均已纳入整改。"
                                : "报告后附问题编号和未纳入原因，可与台账逐条核对。"},
                {"问题变化", "纳入整改范围内：新增 " + data.newFindingCount + " 条，已修复 "
                        + data.fixedFindingCount + " 条。", ""}
        };
    }

    private String recommendationHtml(String recommendation) {
        return java.util.Arrays.stream(recommendation.split("\\R"))
                .map(line -> line.startsWith("覆盖情况：")
                        ? "<b class=\"coverage\">" + escape(line) + "</b>" : escape(line))
                .collect(Collectors.joining("<br/>"));
    }

    private String countExplanationHtml(ReportData data, int groups) {
        StringBuilder html = new StringBuilder("<section class=\"card count-card\"><b>数量说明</b><table class=\"count-lines\">");
        for (String[] row : countExplanationRows(data, groups)) {
            html.append("<tr><th scope=\"row\">").append(escape(row[0]))
                    .append("</th><td><strong>").append(escape(row[1])).append("</strong>");
            if (!row[2].isEmpty()) {
                if ("合并展示".equals(row[0])) html.append("<span class=\"count-note\">").append(escape(row[2])).append("</span>");
                else html.append(" ").append(escape(row[2]));
            }
            html.append("</td></tr>");
        }
        return html.append("</table></section>").toString();
    }

    private String exclusionsHtml(ReportData data) {
        if (data.exclusions.isEmpty()) return "";
        StringBuilder html = new StringBuilder("<h2>未纳入整改的问题及原因</h2><p>以下记录用于台账对账，不计入待整改风险；原因按当前审核记录核对，已归档报告的纳入范围保持不变。</p><table class=\"data\"><thead><tr><th>#</th><th>问题编号 / 规则</th><th>问题 / 位置</th><th>未纳入原因</th></tr></thead><tbody>");
        int index = 1;
        for (ReportExclusion item : data.exclusions) {
            html.append("<tr><td>").append(index++).append("</td><td>F-").append(item.getFindingId())
                    .append("<br/>").append(escape(item.getRuleId())).append("</td><td>")
                    .append(escape(item.getTitle())).append("<br/>").append(escape(item.getFilePath()))
                    .append("</td><td>").append(escape(item.getReason())).append("</td></tr>");
        }
        return html.append("</tbody></table>").toString();
    }

    private String buildReferencesHtml(List<FindingView> findings) {
        StringBuilder html = new StringBuilder();
        for (ReportFindingGroup group : groupFindings(findings)) {
            Map<String, List<String>> sources = group.sources();
            if (sources.isEmpty()) continue;
            if (html.length() == 0) html.append("<h2>附录：整改版本依据</h2><p class=\"foot\">修复记录仅针对所列漏洞；升级须核对 JDK 和依赖兼容性。官方补充规则核实日期：2026-11-10。正文推荐目标按项目 Java 版本筛选，与附录历史版本分别展示。</p>");
            html.append("<section class=\"card\"><b>").append(escape(group.findingIds() + " · " + group.title())).append("</b>");
            for (String record : io.codesentry.finding.service.DependencyRemediationAdvice.versionRecords(group.members)) {
                html.append("<p>").append(escape(record)).append("</p>");
            }
            sources.forEach((url, cves) -> html.append("<p>").append(escape(String.join("、", cves)))
                    .append("：<a href=\"").append(escape(url)).append("\">").append(escape(url)).append("</a></p>"));
            html.append("</section>");
        }
        return html.toString();
    }

    private File resolvePdfSongFont() {
        String configured = System.getenv("CODESENTRY_REPORT_SONG_FONT");
        List<String> candidates = Arrays.asList(configured,
                "/opt/codesentry/fonts/simsun.ttf",
                "docker/fonts/simsun.ttf",
                "/System/Library/Fonts/Supplemental/Songti.ttc",
                "C:/Windows/Fonts/simsun.ttc");
        return firstAvailableFont(candidates, "宋体");
    }

    private File resolvePdfHeiFont() {
        String configured = System.getenv("CODESENTRY_REPORT_HEI_FONT");
        List<String> candidates = Arrays.asList(configured,
                "/opt/codesentry/fonts/simhei.ttf",
                "docker/fonts/simhei.ttf",
                "/System/Library/Fonts/STHeiti Medium.ttc",
                "/System/Library/Fonts/STHeiti Light.ttc",
                "C:/Windows/Fonts/simhei.ttf");
        return firstAvailableFont(candidates, "黑体");
    }

    private File firstAvailableFont(List<String> candidates, String fontName) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                Path path = Paths.get(candidate);
                if (Files.isRegularFile(path)) return path.toFile();
            }
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                "未找到 PDF " + fontName + "字体，请通过 CODESENTRY_REPORT_"
                        + ("宋体".equals(fontName) ? "SONG" : "HEI") + "_FONT 指定字体文件");
    }

    private void configureA4(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr() : document.getDocument().getBody().addNewSectPr();
        CTPageSz pageSize = section.addNewPgSz();
        pageSize.setW(BigInteger.valueOf(11906));
        pageSize.setH(BigInteger.valueOf(16838));
        CTPageMar margin = section.addNewPgMar();
        margin.setTop(BigInteger.valueOf(1080)); margin.setRight(BigInteger.valueOf(1080));
        margin.setBottom(BigInteger.valueOf(1080)); margin.setLeft(BigInteger.valueOf(1080));
    }

    private void addTitle(XWPFDocument document, String text, int size, String color) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.LEFT);
        paragraph.setSpacingAfter(size >= 20 ? 120 : 40);
        styleRun(paragraph.createRun(), text, size, color, true);
    }

    private void addHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(level == 1 ? 260 : 160);
        paragraph.setSpacingAfter(100);
        styleRun(paragraph.createRun(), text, level == 1 ? 16 : 13, "14201B", true);
    }

    private void addText(XWPFDocument document, String text, int size, String color, boolean bold) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(70);
        styleRun(paragraph.createRun(), text, size, color, bold);
    }

    private void addCodeBlock(XWPFDocument document, String code) {
        XWPFTable table = document.createTable(1, 1);
        table.setWidth(9026);
        XWPFTableCell cell = table.getRow(0).getCell(0);
        cell.setColor("F0F3EF");
        XWPFRun run = cell.getParagraphs().get(0).createRun();
        setFontFamily(run, WORD_BODY_FONT, WORD_BODY_FONT);
        run.setFontSize(8); run.setColor("33433A");
        String[] lines = code.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) run.addBreak();
            run.setText(lines[index]);
        }
    }

    private String reportCodeSnippet(String code) {
        String[] lines = code.split("\\R", -1);
        StringBuilder snippet = new StringBuilder(Math.min(code.length(), REPORT_CODE_MAX_CHARS));
        boolean truncated = lines.length > REPORT_CODE_MAX_LINES;
        int lineCount = Math.min(lines.length, REPORT_CODE_MAX_LINES);
        for (int index = 0; index < lineCount; index++) {
            if (index > 0) snippet.append('\n');
            String line = lines[index];
            int remaining = REPORT_CODE_MAX_CHARS - snippet.length();
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (line.length() > remaining) {
                snippet.append(line, 0, remaining);
                truncated = true;
                break;
            }
            snippet.append(line);
        }
        if (truncated) snippet.append(REPORT_CODE_TRUNCATED);
        return snippet.toString();
    }

    private void styleCell(XWPFTableCell cell, String text, boolean bold, String fill, int width) {
        cell.setColor(fill);
        cell.getCTTc().addNewTcPr().addNewTcW().setW(BigInteger.valueOf(width));
        XWPFParagraph paragraph = cell.getParagraphs().get(0);
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        styleRun(paragraph.createRun(), text, bold ? 9 : 15, bold ? "66796E" : "14201B", bold);
    }

    private void styleRun(XWPFRun run, String text, int size, String color, boolean bold) {
        run.setText(text == null ? "" : text);
        String font = bold ? WORD_HEADING_FONT : WORD_BODY_FONT;
        setFontFamily(run, font, font);
        run.setFontSize(size); run.setColor(color); run.setBold(bold);
    }

    private void setFontFamily(XWPFRun run, String westernFont, String eastAsiaFont) {
        run.setFontFamily(westernFont);
        run.setFontFamily(westernFont, XWPFRun.FontCharRange.ascii);
        run.setFontFamily(westernFont, XWPFRun.FontCharRange.hAnsi);
        run.setFontFamily(eastAsiaFont, XWPFRun.FontCharRange.eastAsia);
        run.setFontFamily(eastAsiaFont, XWPFRun.FontCharRange.cs);
    }

    private void embedWordFonts(XWPFDocument document) throws Exception {
        Path song = resolveWordFontFile("CODESENTRY_REPORT_SONG_FONT", "docker/fonts/simsun.ttf",
                "/opt/codesentry/fonts/simsun.ttf", "宋体");
        Path hei = resolveWordFontFile("CODESENTRY_REPORT_HEI_FONT", "docker/fonts/simhei.ttf",
                "/opt/codesentry/fonts/simhei.ttf", "黑体");
        OPCPackage pkg = document.getPackage();
        PackagePartName fontTableName = PackagingURIHelper.createPartName("/word/fontTable.xml");
        PackagePart fontTable = pkg.createPart(fontTableName,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml");
        document.getPackagePart().addRelationship(fontTableName, TargetMode.INTERNAL,
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/fontTable");

        EmbeddedWordFont songFont = addEmbeddedWordFont(pkg, fontTable, song, "simsun");
        EmbeddedWordFont heiFont = addEmbeddedWordFont(pkg, fontTable, hei, "simhei");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:fonts xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + wordFontXml(WORD_BODY_FONT, "SimSun", "roman", songFont)
                + wordFontXml(WORD_HEADING_FONT, "SimHei", "swiss", heiFont)
                + "</w:fonts>";
        try (OutputStream output = fontTable.getOutputStream()) {
            output.write(xml.getBytes(StandardCharsets.UTF_8));
        }
    }

    private Path resolveWordFontFile(String environmentName, String localPath, String containerPath,
                                     String fontName) {
        for (String candidate : Arrays.asList(System.getenv(environmentName), localPath, containerPath)) {
            if (candidate != null && !candidate.isBlank()) {
                Path path = Paths.get(candidate);
                if (Files.isRegularFile(path)) return path;
            }
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                "未找到 Word " + fontName + "字体文件");
    }

    private EmbeddedWordFont addEmbeddedWordFont(OPCPackage pkg, PackagePart fontTable, Path fontFile,
                                                  String partBaseName) throws Exception {
        UUID key = UUID.randomUUID();
        byte[] content = Files.readAllBytes(fontFile);
        byte[] keyBytes = ByteBuffer.allocate(16).putLong(key.getMostSignificantBits())
                .putLong(key.getLeastSignificantBits()).array();
        for (int index = 0; index < Math.min(32, content.length); index++) {
            content[index] ^= keyBytes[15 - (index % 16)];
        }
        PackagePartName partName = PackagingURIHelper.createPartName("/word/fonts/" + partBaseName + ".odttf");
        PackagePart fontPart = pkg.createPart(partName,
                "application/vnd.openxmlformats-officedocument.obfuscatedFont");
        try (OutputStream output = fontPart.getOutputStream()) {
            output.write(content);
        }
        PackageRelationship relationship = fontTable.addRelationship(partName, TargetMode.INTERNAL,
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/font");
        return new EmbeddedWordFont(relationship.getId(), "{" + key.toString().toUpperCase(Locale.ROOT) + "}");
    }

    private String wordFontXml(String name, String alternateName, String family, EmbeddedWordFont embedded) {
        return "<w:font w:name=\"" + name + "\"><w:altName w:val=\"" + alternateName
                + "\"/><w:family w:val=\"" + family + "\"/><w:embedRegular r:id=\""
                + embedded.relationshipId + "\" w:fontKey=\"" + embedded.fontKey + "\"/></w:font>";
    }

    private String javaVersionLabel(ScanProject project) {
        return project.getTargetJavaVersion() == null ? "未确认" : "Java " + project.getTargetJavaVersion();
    }

    private String summaryCell(String label, String value) { return "<td><span>" + escape(label) + "</span><b>" + escape(value) + "</b></td>"; }
    private boolean isDependencyVulnerability(FindingView finding) {
        return "Trivy".equals(finding.getScanner()) && "VULNERABILITY".equals(finding.getCategory());
    }
    private String reportLocationValue(FindingView finding) {
        if (isDependencyVulnerability(finding)) {
            return value(finding.getFilePath(), "未记录依赖文件") + "（依赖组件漏洞，无源码行号）";
        }
        return value(finding.getFilePath(), "unknown") + ":"
                + (finding.getStartLine() == null ? "-" : finding.getStartLine());
    }
    private String reportLocationLabel(FindingView finding) {
        return (isDependencyVulnerability(finding) ? "依赖文件：" : "代码位置：")
                + reportLocationValue(finding);
    }

    private List<ReportFindingGroup> groupFindings(List<FindingView> findings) { return groupFindings(findings, null); }

    private List<ReportFindingGroup> groupFindings(List<FindingView> findings, Integer javaVersion) {
        Map<String, ReportFindingGroup> groups = new LinkedHashMap<>();
        for (FindingView finding : findings) {
            String dependencyKey = DependencyFindingGrouping.groupKey(finding);
            String key = dependencyKey == null ? "finding|" + finding.getId() : "dependency|" + dependencyKey;
            groups.computeIfAbsent(key, ignored -> new ReportFindingGroup(javaVersion)).members.add(finding);
        }
        return new ArrayList<>(groups.values());
    }

    private int severityRank(String severity) {
        if ("CRITICAL".equals(severity)) return 4;
        if ("HIGH".equals(severity)) return 3;
        if ("MEDIUM".equals(severity)) return 2;
        if ("LOW".equals(severity)) return 1;
        return 0;
    }

    private void appendGroupedEvidenceHtml(StringBuilder html, ReportFindingGroup group) {
        for (FindingView member : group.members) {
            html.append("<div class=\"group-review\"><b>")
                    .append(escape(value(member.getRuleId(), "unknown"))).append("</b><small><b>利用条件：</b>")
                    .append(escape(exploitCondition(member))).append("<br/><b>复核证据：</b>")
                    .append(escape(reviewEvidence(member))).append("</small></div>");
        }
    }

    private void appendGroupedAiReviewHtml(StringBuilder html, ReportFindingGroup group,
                                           Map<Long, AiReviewSummary> reviews) {
        for (FindingView member : group.members) {
            AiReviewSummary summary = reviews.get(member.getId());
            html.append("<div class=\"group-review\"><b>")
                    .append(escape(value(member.getRuleId(), "unknown"))).append("：</b>");
            if (summary == null) {
                html.append("<span>暂无工具审核结果</span>");
            } else if ("FAILED".equals(summary.getStatus())) {
                html.append("<span>工具审核失败：")
                        .append(escape(publicReviewText(value(summary.getErrorMessage(), "工具复核未完整执行"))))
                        .append("</span>");
            } else {
                html.append("<span>").append(escape(aiReviewLabel(summary))).append("，可信度 ")
                        .append(escape(confidenceLabel(summary.getConfidence()))).append("</span>");
            }
            html.append("</div>");
        }
    }

    private void addGroupedAiReviewToDocx(XWPFDocument document, FindingView finding, AiReviewSummary summary) {
        String prefix = "  工具复核 " + value(finding.getRuleId(), "unknown") + "：";
        if (summary == null) {
            addText(document, prefix + "暂无结果", 9, "A46400", false);
        } else if ("FAILED".equals(summary.getStatus())) {
            addText(document, prefix + "审核失败，"
                    + publicReviewText(value(summary.getErrorMessage(), "工具复核未完整执行")), 9, "A46400", false);
        } else {
            addText(document, prefix + aiReviewLabel(summary) + "，可信度 "
                    + confidenceLabel(summary.getConfidence()), 9, "A46400", false);
        }
    }
    /**
     * Escape only the five XML syntax characters. Keeping ordinary Unicode characters intact is
     * important because OpenHTMLToPDF parses XHTML and does not define HTML named entities such as
     * {@code &ldquo;}.
     */
    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String value(String value, String fallback) { return hasText(value) ? value : fallback; }
    private String scanModeLabel(ScanRun run) { return "FULL".equals(run.getScanMode().name()) ? "全量扫描" : "增量审查"; }

    private String ruleProfileLabel(String value) {
        if ("LOCAL_OWASP".equals(value)) return "本地 OWASP / CWE 基线";
        if ("COMMUNITY_GENERAL".equals(value)) return "Semgrep 社区通用规则";
        if ("COMPREHENSIVE".equals(value)) return "综合深度审计";
        return value(value, "默认规则集");
    }

    private String severityLabel(String value) {
        if ("CRITICAL".equals(value)) return "严重";
        if ("HIGH".equals(value)) return "高危";
        if ("MEDIUM".equals(value)) return "中危";
        if ("LOW".equals(value)) return "低危";
        return "提示";
    }

    private String severityColor(String value) {
        if ("CRITICAL".equals(value) || "HIGH".equals(value)) return "C83232";
        if ("MEDIUM".equals(value)) return "A46400";
        return "167C6D";
    }

    private String resultLabel(String value) {
        if ("NEW".equals(value)) return "新增";
        if ("EXISTING".equals(value)) return "存量";
        if ("FIXED".equals(value)) return "已修复";
        if ("REOPENED".equals(value)) return "重新出现";
        return escape(value);
    }

    private String triageLabel(String value) {
        if (value == null) return "待复核";
        if ("CONFIRMED".equals(value)) return "确认风险";
        if ("FALSE_POSITIVE".equals(value)) return "误报";
        if ("ACCEPTED_RISK".equals(value)) return "接受风险";
        if ("FIXED_PENDING".equals(value)) return "已整改待验证";
        return escape(value);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private String exploitCondition(FindingView finding) {
        String rule = value(finding.getRuleId(), "unknown").toLowerCase(Locale.ROOT);
        if (rule.contains("cookie-")) return "会话 Cookie 被明文传输或可被脚本读取时，攻击者可窃取会话。";
        if (rule.contains("insecure-hostname")) return "客户端连接不可信 HTTPS 服务时，可能遭受中间人攻击。";
        if (rule.contains("generic-api-key")) return "仓库访问者可读取配置凭据并尝试访问对应外部服务。";
        if (rule.contains("eval")) return "仅当执行内容受外部输入控制时成立。";
        if (rule.contains("tainted-file-path")) return "仅当参数能控制本地文件读取且缺少授权校验时成立；当前调用需核查对象存储权限。";
        if (rule.contains("command-execution")) return "命令或参数受外部输入影响并经 /bin/sh -c 解析时成立。";
        if (rule.contains("md5") || rule.contains("weak-message-digest")) return "仅当用于密码、签名或安全完整性校验时成立。";
        if (rule.contains("regexp")) return "正则模式受外部输入控制且可构造高复杂度时成立。";
        if (rule.contains("old-tls")) return "运行时允许 TLS 1.0/1.1 且服务端能够协商时成立。";
        return "需要结合调用链、输入来源和运行环境确认。";
    }

    private String reviewEvidence(FindingView finding) {
        if (hasText(finding.getTriageComment())) return publicReviewText(finding.getTriageComment());
        return "尚未人工复核；当前证据为 " + value(finding.getScanner(), "unknown") + " / "
                + value(finding.getRuleId(), "unknown") + " 命中代码位置 " + value(finding.getFilePath(), "unknown") + ":"
                + (finding.getStartLine() == null ? "-" : finding.getStartLine()) + "。";
    }

    private String falsePositiveReason(FindingView finding) {
        return hasText(finding.getTriageComment()) ? finding.getTriageComment() : "已标记误报，但尚未填写原因。";
    }

    private void addAiReviewToDocx(XWPFDocument document, AiReviewSummary summary) {
        addAiRoundToDocx(document, "工具初审", summary.getPrimaryReview());
        addAiRoundToDocx(document, "工具复审", summary.getVerificationReview());
        if ("FAILED".equals(summary.getStatus())) {
            addText(document, "工具审核失败：" + publicReviewText(value(summary.getErrorMessage(), "工具复核未完整执行")),
                    9, "A46400", true);
            return;
        }
        addText(document, "工具综合建议（未经人工确认）：" + aiReviewLabel(summary)
                + "    综合可信度：" + confidenceLabel(summary.getConfidence()), 9, "A46400", true);
    }

    private void addAiRoundToDocx(XWPFDocument document, String label, AiReviewRecord review) {
        if (review == null) return;
        double confidence = review.getConfidence() == null ? 0 : review.getConfidence().doubleValue();
        addText(document, label + "（未经人工确认）：" + aiVerdictLabel(review.getVerdict())
                + "    可信度：" + confidenceLabel(confidence), 9, "A46400", true);
    }

    private void appendAiReviewHtml(StringBuilder html, AiReviewSummary summary) {
        if (summary == null) {
            html.append("<span>暂无工具审核结果</span>");
            return;
        }
        appendAiRoundHtml(html, "工具初审", summary.getPrimaryReview());
        appendAiRoundHtml(html, "工具复审", summary.getVerificationReview());
        if ("FAILED".equals(summary.getStatus())) {
            html.append("<b class=\"ai-review\">工具审核失败：</b><span>")
                    .append(escape(publicReviewText(value(summary.getErrorMessage(), "工具复核未完整执行")))).append("</span>");
            return;
        }
        html.append("<b class=\"ai-review\">综合建议：")
                .append(escape(aiReviewLabel(summary))).append("</b><span>综合可信度 ")
                .append(escape(confidenceLabel(summary.getConfidence()))).append("</span>");
    }

    private void appendAiRoundHtml(StringBuilder html, String label, AiReviewRecord review) {
        if (review == null) return;
        double confidence = review.getConfidence() == null ? 0 : review.getConfidence().doubleValue();
        html.append("<b>").append(escape(label)).append("：")
                .append(escape(aiVerdictLabel(review.getVerdict()))).append("</b><span>可信度 ")
                .append(escape(confidenceLabel(confidence))).append("</span>");
    }

    private String publicReviewText(String text) {
        return text == null ? "" : text
                .replaceAll("(?i)OpenAI|DeepSeek(?:[- ]?v[0-9]+[- ]?(?:pro|chat)?)?", "工具服务")
                .replaceAll("(?i)(?<![A-Za-z0-9_])AI(?![A-Za-z0-9_])", "工具")
                .replace("双轮", "工具").replace("两轮", "工具")
                .replaceAll("工具\\s+工具", "工具");
    }

    private String aiVerdictLabel(String verdict) {
        if ("CONFIRMED".equals(verdict)) return "确认风险";
        if ("FALSE_POSITIVE".equals(verdict)) return "疑似误报";
        if ("CONDITIONAL".equals(verdict)) return "条件风险";
        if ("INSUFFICIENT_EVIDENCE".equals(verdict)) return "证据不足";
        return "审核失败";
    }

    private String aiReviewLabel(AiReviewSummary summary) {
        if (summary == null) return "未审核";
        if (summary.isDisagreement()) return "工具结论待确认，必须人工复核";
        return aiVerdictLabel(summary.getConsolidatedVerdict());
    }

    private String confidenceLabel(double confidence) {
        return java.math.BigDecimal.valueOf(confidence).multiply(java.math.BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private final class ReportFindingGroup {
        private final List<FindingView> members = new ArrayList<>();
        private final Integer javaVersion;
        private ReportFindingGroup(Integer javaVersion) { this.javaVersion = javaVersion; }

        private FindingView primary() {
            return members.stream().max(Comparator.comparingInt(item -> severityRank(item.getSeverity())))
                    .orElseThrow(IllegalStateException::new);
        }

        private boolean isGrouped() { return members.size() > 1; }

        private String title() {
            return isGrouped() ? DependencyFindingGrouping.componentLabel(primary()) + " 组件存在 " + members.size() + " 个已知漏洞（合并整改）"
                    : value(primary().getTitle(), "依赖漏洞");
        }

        private String recommendation() {
            return io.codesentry.finding.service.DependencyRemediationAdvice.compact(members, javaVersion);
        }

        private Map<String, List<String>> sources() {
            Map<String, List<String>> sources = new LinkedHashMap<>();
            for (FindingView member : members) {
                for (String url : io.codesentry.finding.service.DependencyRemediationAdvice.references(member)) {
                    List<String> cves = sources.computeIfAbsent(url, key -> new ArrayList<>());
                    if (!cves.contains(member.getRuleId())) cves.add(value(member.getRuleId(), "unknown"));
                }
            }
            return sources;
        }

        private String findingIds() {
            return members.stream().map(item -> "F-" + item.getId()).collect(Collectors.joining("、"));
        }

        private String ruleIds() {
            return members.stream().map(item -> value(item.getRuleId(), "unknown"))
                    .distinct().collect(Collectors.joining("、"));
        }

        private String resultLabels() {
            return members.stream().map(item -> resultLabel(item.getResultStatus()))
                    .distinct().collect(Collectors.joining("、"));
        }

        private String triageLabels() {
            return members.stream().map(item -> triageLabel(item.getTriageStatus()))
                    .distinct().collect(Collectors.joining("、"));
        }
    }

    private static class ReportData {
        private final ScanRun run;
        private final ScanProject project;
        private final List<FindingView> findings;
        private final List<ReportExclusion> exclusions = new ArrayList<>();
        private final int ledgerFindingCount;
        private final int notIncludedFindingCount;
        private final int newFindingCount;
        private final int fixedFindingCount;
        private final Map<Long, AiReviewSummary> aiReviews;

        private ReportData(ScanRun run, ScanProject project, List<FindingView> findings,
                           Map<Long, AiReviewSummary> aiReviews, List<FindingView> raw) {
            this.ledgerFindingCount = raw.size();
            java.util.Set<Long> includedIds = findings.stream().map(FindingView::getId).collect(Collectors.toSet());
            this.notIncludedFindingCount = (int) raw.stream().filter(f -> !includedIds.contains(f.getId())).count();
            java.util.Set<Long> selectedIds = ReviewedFindingPolicy.select(raw, aiReviews).stream()
                    .map(FindingView::getId).collect(Collectors.toSet());
            for (FindingView finding : raw) {
                if (includedIds.contains(finding.getId())) continue;
                String reason = ReviewedFindingPolicy.exclusionReason(finding, aiReviews.get(finding.getId()));
                if (reason == null) reason = selectedIds.contains(finding.getId())
                        ? "不在已归档报告范围内，当前审核已符合条件；需通过报告更新流程重新纳入"
                        : "同位置、同规则或同类规则重复命中，报告去重后保留其他记录";
                exclusions.add(new ReportExclusion(finding, reason));
            }
            this.run = run;
            this.project = project;
            this.findings = findings;
            this.newFindingCount = (int) findings.stream().filter(finding ->
                    "NEW".equals(finding.getResultStatus()) || "REOPENED".equals(finding.getResultStatus())).count();
            this.fixedFindingCount = (int) findings.stream().filter(finding -> "FIXED".equals(finding.getResultStatus())).count();
            this.aiReviews = aiReviews;
        }

        private String reviewStatus() {
            if (findings.isEmpty()) return "无问题，无需复核";
            long reviewed = findings.stream().filter(finding -> finding.getTriageStatus() != null).count();
            if (reviewed == 0) return "自动扫描完成，人工复核未完成";
            if (reviewed < findings.size()) return "人工复核进行中（" + reviewed + "/" + findings.size() + "）";
            return "人工复核已完成";
        }
    }

    private static final class EmbeddedWordFont {
        private final String relationshipId;
        private final String fontKey;

        private EmbeddedWordFont(String relationshipId, String fontKey) {
            this.relationshipId = relationshipId;
            this.fontKey = fontKey;
        }
    }
}
