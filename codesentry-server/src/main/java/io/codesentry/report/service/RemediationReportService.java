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
import java.util.Set;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 统一计算报告采纳、数量对账与依赖合并建议，并生成保留来源证据的多格式整改报告。 */
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

    /** 注入原扫描、资产、问题、工具复核和归档存储，统一三种导出数据来源。 */
    public RemediationReportService(ScanRunService scanRunService, ScanProjectService projectService,
                                    FindingPersistenceService findingService, AiReviewService aiReviewService, ReportSnapshotStore snapshots) {
        this.scanRunService = scanRunService;
        this.projectService = projectService;
        this.findingService = findingService;
        this.aiReviewService = aiReviewService;
        this.snapshots = snapshots;
    }

    /** 按原扫描加载并归档采纳清单，生成带转义源码的在线整改报告。 */
    public byte[] exportHtml(Long runId) {
        return buildHtml(load(runId, true)).getBytes(StandardCharsets.UTF_8);
    }

    /** 读取原报告已采纳问题 ID，不合并或重编号原始问题。 */
    public List<Long> includedFindingIds(Long runId) {
        // 保留原接口对不存在扫描/资产的校验，轻量化只改变读取成本。
        ScanRun run = scanRunService.requireById(runId);
        projectService.requireById(run.getProjectId());
        List<Long> archived = snapshots.readIds(runId);
        if (archived != null) return archived;
        // 与导出复用同一双轮审核和去重规则，只取政策字段，不生成整份报告。
        Map<Long, io.codesentry.ai.dto.AiReviewSummary> reviews = new java.util.HashMap<>();
        aiReviewService.latestPolicyForRun(runId).forEach(review -> reviews.put(review.getFindingId(), review));
        return ReviewedFindingPolicy.select(findingService.findPolicyByRunId(runId), reviews).stream()
                .map(FindingView::getId).collect(Collectors.toList());
    }

    /** 读取未采纳问题及排除理由，支持与台账逐项对账。 */
    public List<ReportExclusion> excludedFindings(Long runId) { return load(runId, false).exclusions; }

    /** 按项目显示名生成下载名称，类型继续在报告正文说明。 */
    public String exportFileName(Long runId, String extension) {
        ScanRun run = scanRunService.requireById(runId);
        ScanProject project = projectService.requireById(run.getProjectId());
        return ReportFileNames.securityScan(project.getDisplayName(), extension);
    }

    /** 兼容最近报告列表接口，仍沿用原采纳统计；完整历史由分页接口提供。 */
    public List<RemediationReportSummary> recent(int limit) { return recent(limit,null,null); }

    /** 兼容最近报告列表接口，仍沿用原采纳统计；完整历史由分页接口提供。 */
    public List<RemediationReportSummary> recent(int limit,Long businessProjectId,Long assetId) {
        List<RemediationReportSummary> reports = summarize(scanRunService.recentForReports(limit,businessProjectId,assetId));
        reports.sort(Comparator.comparing(RemediationReportSummary::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return reports;
    }

    /**
     * 按项目和资产查询完整历史报告；只限制单页计算量，不限制可翻阅的历史总量。
     * 同一只读快照内查询总数和分页，避免后台扫描完成时总数与列表范围不一致。
     * @param requestedPage 从 1 开始的页码，超出范围时回到末页
     * @param requestedSize 单页条数，限制为 1 至 100 以控制报告统计开销
     * @param businessProjectId 可选业务项目 ID，按资产当前归属筛选
     * @param assetId 可选扫描资产 ID，同时指定项目时取交集
     * @return 当前页报告、归一化页码及完整匹配总数
     */
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public io.codesentry.report.dto.ReportPage page(int requestedPage,int requestedSize,Long businessProjectId,Long assetId) {
        int size=Math.max(1,Math.min(100,requestedSize));
        long total=scanRunService.countForReports(businessProjectId,assetId);
        long last=Math.max(1,(total+size-1)/size);
        int page=(int)Math.min(Math.max(1,requestedPage),last);
        List<RemediationReportSummary> items=total==0?List.of():summarize(scanRunService.pageForReports(size,(long)(page-1)*size,businessProjectId,assetId));
        return new io.codesentry.report.dto.ReportPage(items,total,page,size);
    }

    /** 将当前页扫描转换为报告对账统计，沿用原始归档采纳规则及联网核实状态。 */
    private List<RemediationReportSummary> summarize(List<ScanRun> runs) {
        List<RemediationReportSummary> reports = new ArrayList<>();
        for (ScanRun run : runs) {
            if (run.getStatus() != io.codesentry.scan.model.ScanRunStatus.COMPLETED
                    && run.getStatus() != io.codesentry.scan.model.ScanRunStatus.PARTIAL) continue;
            List<FindingView> raw = findingService.findPolicyByRunId(run.getId());
            List<FindingView> visible = snapshots.readSummary(run.getId());
            int qualityCount=(int)raw.stream().filter(f->"QUALITY".equals(f.getIssueType())).count();
            if (visible == null || visible.stream().noneMatch(f->"QUALITY".equals(f.getIssueType())))
                raw=raw.stream().filter(f->!"QUALITY".equals(f.getIssueType())).collect(Collectors.toList());
            if (visible == null) {
                Map<Long,AiReviewSummary> reviews = new HashMap<>();
                aiReviewService.latestPolicyForRun(run.getId()).forEach(review -> reviews.put(review.getFindingId(),review));
                visible = ReviewedFindingPolicy.select(raw,reviews);
            }
            Set<Long> included = visible.stream().map(FindingView::getId).collect(Collectors.toSet());
            int newCount = (int) visible.stream().filter(f -> "NEW".equals(f.getResultStatus()) || "REOPENED".equals(f.getResultStatus())).count();
            int excluded = (int) raw.stream().filter(f -> !included.contains(f.getId())).count();
            reports.add(new RemediationReportSummary(run.getId(), run.getProjectId(), run.getScanMode(),
                    run.getStatus(), visible.size(), newCount, run.getCreatedAt(),
                    raw.size(), groupFindings(visible).size(), excluded).withRemediation(run)
                    .withClassification(qualityCount,(int)visible.stream().filter(f->"QUALITY".equals(f.getIssueType())).count()));
        }
        return reports;
    }

    /** 以统一报告数据生成 PDF，保留分页和中文字体。 */
    public byte[] exportPdf(Long runId) {
        ReportData data = load(runId, true);
        return renderPdfHtml(buildPdfHtml(data));
    }

    /** 按轮次保存的资产身份生成项目汇总中的单资产章节。 */
    public String projectSectionHtml(Long runId, ScanProject asset) { return buildPdfHtml(load(runId, true, asset)); }

    /** 使用本地中文字体将报告 HTML 转换为 PDF，不访问外部资源。 */
    public byte[] renderPdfHtml(String html) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
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

    /** 导出原资产整改报告的 Word 文档。 */
    public byte[] exportDocx(Long runId) { return exportProjectDocx(runId, null); }

    /** 生成独立或项目章节使用的 Word 内容，沿用原采纳清单与资产版本身份。 */
    public byte[] exportProjectDocx(Long runId, ScanProject asset) {
        ReportData data = load(runId, true, asset);
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
            for (String identity : assetIdentityLines(data)) addText(document, identity, 11, "C83232", true);
            addText(document, runtimeDescription(data.project), 10, "14201B", false);
            if (data.run.getNetworkMode() == io.codesentry.scan.model.NetworkMode.ONLINE)
                addText(document, "联网扫描：" + value(data.run.getRemediationMessage(), "联网修复依据尚未完成核实"), 10, "A46400", true);
            addText(document, (isJar(data.project) ? "扫描文件：" : "扫描分支：") + (isJar(data.project) ? jarFileName(data) : value(data.run.getScanRef(), "当前工作区"))
                    + "    基线分支：" + value(data.run.getBaselineRef(), "无"), 10, "14201B", false);
            addText(document, "扫描提交：" + value(data.run.getScanCommit(), "-")
                    + "    merge-base：" + value(data.run.getMergeBaseCommit(), "-"), 9, "66796E", false);

            addHeading(document, "问题明细与整改建议", 1);
            if (data.findings.isEmpty()) {
                addText(document, "当前没有符合报告筛选条件的问题，不代表原始扫描未发现风险。", 10, "66796E", false);
            }
            int index = 1;
            for (ReportFindingGroup group : groupForProject(data.findings, data.project, data.aiReviews)) {
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

            List<ReportFindingGroup> sourceGroups = groupForProject(data.findings, data.project, data.aiReviews).stream()
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
            if (asset == null) embedWordFonts(document);
            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Word 报告生成失败：" + safeMessage(exception));
        }
    }

    /** 取得并固定该报告采纳的问题清单，供项目汇总归档复用。 */
    public List<FindingView> archiveFindings(Long runId) { return load(runId, true).findings; }

    /** 加载原扫描、资产和问题；archive 为真时固定采纳清单，资产覆盖值仅用于历史身份展示。 */
    private ReportData load(Long runId, boolean archive) { return load(runId, archive, null); }

    /** 加载原扫描、资产和问题；archive 为真时固定采纳清单，资产覆盖值仅用于历史身份展示。 */
    private ReportData load(Long runId, boolean archive, ScanProject asset) {
        ScanRun run = scanRunService.requireById(runId);
        if (archive && run.getStatus() != io.codesentry.scan.model.ScanRunStatus.COMPLETED
                && run.getStatus() != io.codesentry.scan.model.ScanRunStatus.PARTIAL) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "扫描结束后才能归档和导出报告");
        }
        ScanProject project = projectService.requireById(run.getProjectId());
        if (asset != null) project = asset;
        List<FindingView> raw = findingService.findStoredByRunId(runId);
        int qualityCount=(int)raw.stream().filter(f->"QUALITY".equals(f.getIssueType())).count();
        List<FindingView> visible = snapshots.read(runId);
        // 安全归档仍按安全范围列附录；旧归档实际纳入规范时，保留其原始采纳范围。
        if(visible==null || visible.stream().noneMatch(f->"QUALITY".equals(f.getIssueType())))raw=raw.stream().filter(f->!"QUALITY".equals(f.getIssueType())).collect(Collectors.toList());
        io.codesentry.finding.service.OnlineRemediationService.attach(run, raw);
        Map<Long, AiReviewSummary> aiReviews = new LinkedHashMap<>();
        aiReviewService.latestForRun(runId).forEach(summary -> aiReviews.put(summary.getFindingId(), summary));
        if (visible == null) {
            visible = io.codesentry.finding.service.ReviewedFindingPolicy.select(raw, aiReviews);
            // 仅采纳项需要源码证据；未采纳的数千条问题只进入排除清单。
            findingService.enrichSnippets(visible);
            if (archive) visible = snapshots.freeze(runId, visible);
        }
        io.codesentry.finding.service.OnlineRemediationService.attach(run, visible);
        return new ReportData(run, project, visible, aiReviews, raw, qualityCount);
    }


    /** Keep the browser's table layout; only adapt spacing and pagination for PDF. */
    private String buildPdfHtml(ReportData data) {
        String html = buildHtml(data);
        for (String heading : Arrays.asList("问题明细与整改建议", "未纳入整改的问题及原因", "附录：整改版本依据")) {
            html = html.replace("<h2>" + heading + "</h2>", "<h2 class=\"pdf-section\">" + heading + "</h2>");
        }
        String css = "@page{size:A4 landscape;margin:10mm 10mm 13mm;"
                + "@bottom-center{content:'第 ' counter(page) ' / ' counter(pages) ' 页';font-family:'CodeSentrySong';font-size:9px;color:#66796e}}"
                + "body{font-size:12px;line-height:1.35}.wrap{padding:0}.cover{padding:17px 22px;margin-bottom:12px}"
                + "h1{font-size:25px;margin:9px 0 6px}.brand{font-size:11px}.meta{font-size:10px}"
                + ".summary-table{margin:0 0 12px;border-spacing:6px 0;page-break-inside:avoid}.summary-table td{padding:11px 12px}.summary-table b{font-size:22px;margin-top:4px}"
                + ".card{padding:12px 14px;margin-bottom:12px;page-break-inside:auto}.count-card{page-break-inside:avoid}.count-card>b{font-size:15px}"
                + ".count-lines{font-size:12px;line-height:1.4;margin-top:8px}.count-lines th,.count-lines td{padding:6px 0}.count-note{font-size:10px;margin-top:2px}"
                + ".card p{margin:7px 0}.foot{font-size:9px;line-height:1.35;margin:8px 0}"
                + "h2.pdf-section{page-break-before:always;page-break-after:avoid;font-size:18px;margin:0 0 10px;padding-top:0}"
                + ".data{-fs-table-paginate:paginate;font-size:10px;line-height:1.35}.data thead{display:table-header-group}"
                + ".data tr,.data td{page-break-inside:auto}.data th,.data td{padding:7px 6px}.data th{font-size:10px}"
                + ".group-review{margin:0 0 6px;padding-bottom:6px}.card>b{page-break-after:avoid}"
                + "a,code,pre{word-wrap:break-word}p{orphans:2;widows:2}";
        return html.replace("</style>", css + "</style>");
    }

    /** 构建报告正文、统计、原始问题明细和未纳入项，所有业务文本需转义。 */
    private String buildHtml(ReportData data) {
        StringBuilder rows = new StringBuilder();
        int index = 1;
        List<ReportFindingGroup> groups = groupForProject(data.findings, data.project, data.aiReviews);
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
                "<section class=\"card\"><b>扫描基线</b>" + assetIdentityHtml(data) + "<p>" + escape(runtimeDescription(project)) + "</p>"
                + (run.getNetworkMode() == io.codesentry.scan.model.NetworkMode.ONLINE
                    ? "<p class=\"ai-review\">联网扫描：" + escape(value(run.getRemediationMessage(), "联网修复依据尚未完成核实")) + "</p>" : "")
                + "<p>" + (isJar(project) ? "扫描文件：" : "扫描分支：") +
                escape(isJar(project) ? jarFileName(data) : value(run.getScanRef(), "当前工作区")) + "；基线分支：" + escape(value(run.getBaselineRef(), "无")) +
                "；扫描提交：" + escape(value(run.getScanCommit(), "-")) + "；merge-base：" + escape(value(run.getMergeBaseCommit(), "-")) +
                "</p></section>" + (groups.size() < data.findings.size() ? "<p class=\"foot\">合并说明：同一依赖文件与组件版本的多个 CVE 在明细中合并展示；统计仍按 CVE 数量计算。</p>" : "") +
                "<p class=\"foot\">人工复核状态：" + escape(data.reviewStatus()) +
                "报告保留人工已确认的风险，以及工具复核完成、通过可信度门槛且未判为误报的问题；人工判为误报的不纳入报告，可信度不代表漏洞可利用性。" +
                "本报告由 CodeSentry 本地节点生成。</p><h2>问题明细与整改建议</h2><table class=\"data\"><thead><tr><th style=\"width:3%\">#</th><th style=\"width:6%\">等级</th>" +
                "<th style=\"width:18%\">问题 / 规则 / 代码片段</th><th style=\"width:14%\">位置</th><th style=\"width:6%\">变化</th><th style=\"width:8%\">人工结论</th><th style=\"width:15%\">利用条件 / 复核证据</th><th style=\"width:16%\">工具复核</th><th style=\"width:14%\">整改建议</th>" +
                "</tr></thead><tbody>" + (rows.length() == 0 ? "<tr><td colspan=\"9\">当前没有符合报告筛选条件的问题，不代表原始扫描未发现风险。</td></tr>" : rows) +
                "</tbody></table>" + exclusionsHtml(data) + buildReferencesHtml(data.findings) + "</main></body></html>";
    }


    /** 生成台账、采纳、合并及变化数量说明，区分问题条数与整改组数。 */
    private String[][] countExplanationRows(ReportData data, int groups) {
        String[][] rows=new String[][] {
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
        if(data.qualityFindingCount==0)return rows;
        return java.util.stream.Stream.concat(java.util.Arrays.stream(rows),java.util.stream.Stream.of(new String[][]{{"编码规范",data.qualityFindingCount+" 条规范问题另行维护整改清单。","新安全报告不混入规范风险；已归档报告保留原始范围。"}})).toArray(String[][]::new);
    }

    /** 将整改建议文本转换为安全的报告段落，不执行内嵌 HTML。 */
    private String recommendationHtml(String recommendation) {
        return java.util.Arrays.stream(recommendation.split("\\R"))
                .map(line -> line.startsWith("覆盖情况：")
                        ? "<b class=\"coverage\">" + escape(line) + "</b>" : escape(line))
                .collect(Collectors.joining("<br/>"));
    }

    /** 以 HTML 展示与原台账可逐项核对的数量口径。 */
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

    /** 列出未纳入正文的问题编号和原因，保留排除项的可追溯性。 */
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

    /** 汇总依据链接与原始 CVE 来源，减少重复引用。 */
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

    /** 整理报告使用的证据片段，控制文档长度并保持原问题定位。 */
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

    public void embedWordFonts(XWPFDocument document) throws Exception {
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

    /** 判断资产是否为上传 JAR，用于类型标识与包名展示。 */
    private boolean isJar(ScanProject project) {
        return project.getSourceType() == io.codesentry.project.model.ProjectSourceType.JAR_UPLOAD;
    }

    /** 优先读取本次扫描保存的 JAR 包名，避免后续替换文件改变报告来源。 */
    private String jarFileName(ReportData data) {
        // The recorded artifact belongs to this scan, even when the registered JAR is replaced later.
        String path = data.run.getArtifactPath();
        if (path == null || path.isBlank()) path = data.run.getScanRef();
        if (path == null || path.isBlank()) path = data.project.getLocalPath();
        if (path == null || path.isBlank()) return "未记录 JAR 文件名";
        path = path.replace('\\', '/');
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** 生成资产类型、名称及 Git/目录/JAR 来源说明。 */
    private List<String> assetIdentityLines(ReportData data) {
        ScanProject asset = data.project;
        String name = "扫描资产：" + value(asset.getDisplayName(), "未命名资产");
        if (isJar(asset)) return List.of("资产类型：JAR 包（上传产物）", name, "JAR 文件：" + jarFileName(data));
        if (asset.getSourceType() == io.codesentry.project.model.ProjectSourceType.GIT) {
            String url = value(asset.getRemoteUrl(), "未记录 Git 地址").replaceFirst("(?i)^(https?://)[^/@]+@", "$1");
            return List.of("资产类型：代码（远程 Git 仓库）", name, "登记 Git 仓库：" + url);
        }
        return List.of("资产类型：代码（本地仓库）", name, "代码项目目录：" + value(asset.getLocalPath(), "未记录本地目录"));
    }

    /** 将资产身份说明安全转义后以醒目的格式输出。 */
    private String assetIdentityHtml(ReportData data) {
        return assetIdentityLines(data).stream().map(line -> "<p class=\"asset-identity\" style=\"color:#c83232;font-weight:700\"><b>" + escape(line) + "</b></p>")
                .collect(Collectors.joining());
    }

    /** 按前后端应用类型描述业务 Java 或 Node.js 运行版本。 */
    private String runtimeDescription(ScanProject project) {
        String node = project.getTargetNodeVersion() == null ? "未确认" : "Node.js " + project.getTargetNodeVersion();
        if (project.getApplicationType() == io.codesentry.project.model.ApplicationType.FRONTEND) return "业务 Node.js 版本：" + node;
        String java = "业务 Java 版本：" + javaVersionLabel(project);
        return project.getApplicationType() == io.codesentry.project.model.ApplicationType.FULL_STACK ? java + "；业务 Node.js 版本：" + node : java;
    }

    /** 将业务 Java 版本转换为可展示标签，未配置时保留未知状态。 */
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

    /** 按依赖文件和组件版本合并整改展示，保留组内原问题编号与 CVE。 */
    private List<ReportFindingGroup> groupFindings(List<FindingView> findings) { return groupFindings(findings, null); }

    /** 按依赖文件和组件版本合并整改展示，保留组内原问题编号与 CVE。 */
    private List<ReportFindingGroup> groupFindings(List<FindingView> findings, Integer javaVersion) {
        Map<String, ReportFindingGroup> groups = new LinkedHashMap<>();
        for (FindingView finding : findings) {
            String dependencyKey = DependencyFindingGrouping.groupKey(finding);
            String key = dependencyKey == null ? "finding|" + finding.getId() : "dependency|" + dependencyKey;
            groups.computeIfAbsent(key, ignored -> new ReportFindingGroup(javaVersion)).members.add(finding);
        }
        return new ArrayList<>(groups.values());
    }

    /** 根据资产应用类型及运行版本选择依赖整改建议。 */
    private List<ReportFindingGroup> groupForProject(List<FindingView> findings, ScanProject project, Map<Long,AiReviewSummary> reviews) {
        List<ReportFindingGroup> groups = groupFindings(findings, project.getTargetJavaVersion());
        for (ReportFindingGroup group : groups) {
            FindingView first = group.primary();
            group.review = reviews.get(first.getId());
            String path = value(first.getFilePath(), "").toLowerCase(Locale.ROOT);
            boolean nodePath = path.contains("package-lock.json") || path.contains("package.json") || path.contains("yarn.lock") || path.contains("pnpm-lock") || path.contains("node_modules");
            if (project.getApplicationType() == io.codesentry.project.model.ApplicationType.FRONTEND
                    || (project.getApplicationType() == io.codesentry.project.model.ApplicationType.FULL_STACK && nodePath))
                group.nodeRuntime = project.getTargetNodeVersion() == null ? "未确认" : project.getTargetNodeVersion();
        }
        return groups;
    }

    /** 提供面向前端依赖的整改与复测要求，不套用 Java 兼容性约束。 */
    private String nodeDependencyAdvice(List<FindingView> findings, String runtime) {
        StringBuilder advice = new StringBuilder("项目 Node.js 版本：").append(runtime).append("。\n");
        java.util.regex.Pattern fixed = java.util.regex.Pattern.compile("本漏洞修复版本（扫描时漏洞库记录）：([^。\\n]+)");
        for (FindingView finding : findings) {
            java.util.regex.Matcher match = fixed.matcher(value(finding.getRecommendation(), ""));
            advice.append(value(finding.getRuleId(), "漏洞")).append("：").append(match.find() ? "扫描记录的修复版本 " + match.group(1) : "扫描记录未提供明确修复版本，需核对上游公告").append("。\n");
        }
        return advice.append("处理：逐条核对漏洞修复范围，更新 package.json 与锁文件，确认依赖 engines.node 及构建工具与当前 Node.js 版本兼容；安装、构建和业务回归测试后复扫。扫描记录的修复版本不代表已验证项目兼容性。").toString();
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
        private String nodeRuntime;
        private AiReviewSummary review;
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
            if (nodeRuntime != null && isDependencyVulnerability(primary())) return nodeDependencyAdvice(members, nodeRuntime);
            String advice = io.codesentry.finding.service.DependencyRemediationAdvice.compact(members, javaVersion);
            // 版本约束仍由依赖整改模块负责；代码问题补上已经完成的工具复核建议，不再次调用模型。
            if (!isDependencyVulnerability(primary()) && review != null && !"FAILED".equals(review.getStatus())) {
                AiReviewRecord verified = review.getVerificationReview();
                if (verified != null && "COMPLETED".equals(verified.getStatus()) && hasText(verified.getRecommendation()))
                    advice += "\n工具复核补充建议（需人工确认）：" + publicReviewText(verified.getRecommendation());
            }
            return advice;
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
        private final int qualityFindingCount;
        private final int ledgerFindingCount;
        private final int notIncludedFindingCount;
        private final int newFindingCount;
        private final int fixedFindingCount;
        private final Map<Long, AiReviewSummary> aiReviews;

        private ReportData(ScanRun run, ScanProject project, List<FindingView> findings,
                           Map<Long, AiReviewSummary> aiReviews, List<FindingView> raw, int qualityCount) {
            this.qualityFindingCount=qualityCount;
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
