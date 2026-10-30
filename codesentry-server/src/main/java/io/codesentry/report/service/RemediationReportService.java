package io.codesentry.report.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.codesentry.common.error.BusinessException;
import io.codesentry.ai.dto.AiReviewSummary;
import io.codesentry.ai.model.AiReviewRecord;
import io.codesentry.ai.service.AiReviewService;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.FindingPersistenceService;
import io.codesentry.project.model.ScanProject;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.report.dto.RemediationReportSummary;
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
import org.springframework.web.util.HtmlUtils;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

    public RemediationReportService(ScanRunService scanRunService, ScanProjectService projectService,
                                    FindingPersistenceService findingService, AiReviewService aiReviewService) {
        this.scanRunService = scanRunService;
        this.projectService = projectService;
        this.findingService = findingService;
        this.aiReviewService = aiReviewService;
    }

    public byte[] exportHtml(Long runId) {
        return buildHtml(load(runId)).getBytes(StandardCharsets.UTF_8);
    }

    public List<RemediationReportSummary> recent(int limit) {
        List<RemediationReportSummary> reports = new ArrayList<>();
        for (ScanRun run : scanRunService.recent(limit)) {
            if (run.getStatus() != io.codesentry.scan.model.ScanRunStatus.COMPLETED
                    && run.getStatus() != io.codesentry.scan.model.ScanRunStatus.PARTIAL) continue;
            ReportData data = load(run.getId());
            reports.add(new RemediationReportSummary(run.getId(), run.getProjectId(), run.getScanMode(),
                    run.getStatus(), data.findings.size(), data.newFindingCount));
        }
        return reports;
    }

    public byte[] exportPdf(Long runId) {
        ReportData data = load(runId);
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
        ReportData data = load(runId);
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            configureA4(document);
            addTitle(document, "CODESENTRY / REMEDIATION REPORT", 11, "78A921");
            addTitle(document, "代码安全扫描整改报告", 24, "14201B");
            addText(document, "报告编号 CSR-" + String.format("%06d", data.run.getId())
                    + "    项目 " + data.project.getProjectName()
                    + "    生成时间 " + TIME.format(LocalDateTime.now()), 10, "66796E", false);

            XWPFTable summary = document.createTable(2, 4);
            summary.setWidth(9026);
            String[] labels = {"扫描模式", "当前问题", "新增问题", "已修复"};
            String[] values = {scanModeLabel(data.run), String.valueOf(data.findings.size()),
                    String.valueOf(data.newFindingCount), String.valueOf(data.fixedFindingCount)};
            for (int column = 0; column < 4; column++) {
                styleCell(summary.getRow(0).getCell(column), labels[column], true, "E9EFE9", 2256);
                styleCell(summary.getRow(1).getCell(column), values[column], false, "FFFFFF", 2256);
            }

            addHeading(document, "扫描基线", 1);
            addText(document, "规则集：" + ruleProfileLabel(data.run.getRuleProfile()), 10, "14201B", false);
            addText(document, "扫描分支：" + value(data.run.getScanRef(), "当前工作区")
                    + "    基线分支：" + value(data.run.getBaselineRef(), "无"), 10, "14201B", false);
            addText(document, "扫描提交：" + value(data.run.getScanCommit(), "-")
                    + "    merge-base：" + value(data.run.getMergeBaseCommit(), "-"), 9, "66796E", false);

            addHeading(document, "问题明细与整改建议", 1);
            if (data.findings.isEmpty()) {
                addText(document, "当前没有符合报告筛选条件的问题，不代表原始扫描未发现风险。", 10, "66796E", false);
            }
            int index = 1;
            for (FindingView finding : data.findings) {
                XWPFParagraph heading = document.createParagraph();
                heading.setSpacingBefore(220);
                heading.setSpacingAfter(80);
                styleRun(heading.createRun(), String.format("%02d  ", index++), 11, "78A921", true);
                styleRun(heading.createRun(), "[" + severityLabel(finding.getSeverity()) + "] ", 11,
                        severityColor(finding.getSeverity()), true);
                styleRun(heading.createRun(), finding.getTitle(), 11, "14201B", true);

                addText(document, "扫描器 / 规则：" + finding.getScanner() + " / " + finding.getRuleId(),
                        9, "66796E", false);
                addText(document, reportLocationLabel(finding), 9, "66796E", false);
                addText(document, "变化状态：" + resultLabel(finding.getResultStatus())
                        + "    人工结论：" + triageLabel(finding.getTriageStatus()), 9, "66796E", false);
                addText(document, "利用条件：" + exploitCondition(finding), 9, "38483F", false);
                addText(document, "复核证据：" + reviewEvidence(finding), 9, "38483F", false);
                AiReviewSummary aiReview = data.aiReviews.get(finding.getId());
                if (aiReview != null) {
                    addAiReviewToDocx(document, aiReview);
                }
                if ("FALSE_POSITIVE".equals(finding.getTriageStatus())) {
                    addText(document, "误报原因：" + falsePositiveReason(finding), 9, "A46400", false);
                }
                if (hasText(finding.getCodeSnippet())) {
                    addCodeBlock(document, reportCodeSnippet(finding.getCodeSnippet()));
                }
                if (hasText(finding.getDescription()) && !finding.getDescription().equals(finding.getTitle())) {
                    addText(document, "问题说明：" + finding.getDescription(), 9, "38483F", false);
                }
                addText(document, "整改建议：" + value(finding.getRecommendation(),
                        "请结合代码上下文复核并按安全编码规范整改。"), 9, "38483F", false);
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

    private ReportData load(Long runId) {
        ScanRun run = scanRunService.requireById(runId);
        ScanProject project = projectService.requireById(run.getProjectId());
        List<FindingView> raw = findingService.findByRunId(runId);
        Map<Long, AiReviewSummary> aiReviews = new LinkedHashMap<>();
        aiReviewService.latestForRun(runId).forEach(summary -> aiReviews.put(summary.getFindingId(), summary));
        List<FindingView> visible = io.codesentry.finding.service.ReviewedFindingPolicy.select(raw, aiReviews);
        return new ReportData(run, project, visible, aiReviews);
    }


    private String buildHtml(ReportData data) {
        StringBuilder rows = new StringBuilder();
        int index = 1;
        for (FindingView finding : data.findings) {
            rows.append("<tr><td>").append(index++).append("</td><td><span class=\"severity ")
                    .append(escape(finding.getSeverity().toLowerCase())).append("\">")
                    .append(severityLabel(finding.getSeverity())).append("</span></td><td><b>")
                    .append(escape(finding.getTitle())).append("</b><small>")
                    .append(escape(finding.getScanner())).append(" / ").append(escape(finding.getRuleId()))
                    .append("</small>");
            if (hasText(finding.getCodeSnippet())) {
                rows.append("<pre>").append(escape(reportCodeSnippet(finding.getCodeSnippet()))).append("</pre>");
            }
            rows.append("</td><td><code>").append(escape(reportLocationValue(finding)))
                    .append("</code></td><td>")
                    .append(resultLabel(finding.getResultStatus())).append("</td><td>")
                    .append(triageLabel(finding.getTriageStatus())).append("</td><td>")
                    .append("<b>利用条件：</b>").append(escape(exploitCondition(finding)))
                    .append("<br/><b>复核证据：</b>").append(escape(reviewEvidence(finding)));
            if ("FALSE_POSITIVE".equals(finding.getTriageStatus())) {
                rows.append("<br/><b class=\"false-positive\">误报原因：</b>")
                        .append(escape(falsePositiveReason(finding)));
            }
            AiReviewSummary aiReview = data.aiReviews.get(finding.getId());
            rows.append("</td><td class=\"ai-cell\">");
            appendAiReviewHtml(rows, aiReview);
            rows.append("</td><td>")
                    .append(escape(value(finding.getRecommendation(), "请结合代码上下文复核并按安全编码规范整改。")))
                    .append("</td></tr>");
        }

        ScanRun run = data.run;
        ScanProject project = data.project;
        return "<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh-CN\"><head><meta charset=\"utf-8\" />" +
                "<title>CodeSentry 整改报告</title><style>@page{size:A4 landscape;margin:14mm}*{box-sizing:border-box}" +
                "body{font-family:'CodeSentrySong','SimSun',serif;margin:0;color:#14201b;background:#f4f7f3;font-size:12px}" +
                ".cover{padding:32px 38px;background:#0b1714;color:#eef4ec;border-bottom:6px solid #adf33f}.brand{letter-spacing:.12em;color:#adf33f;font-weight:800}" +
                "h1{margin:14px 0 8px;font-size:28px}.meta{color:#aec0b6}.wrap{padding:24px 38px 0}.brand,h1,h2,th,b{font-family:'CodeSentryHei','SimHei',sans-serif}.summary-table{width:100%;margin-bottom:22px;border-collapse:separate;border-spacing:10px 0}" +
                ".summary-table td{width:25%;background:white;border:1px solid #d9e2dc;padding:14px}.summary-table span{color:#66796e;font-size:11px}.summary-table b{display:block;font-size:24px;margin-top:7px}" +
                ".card{background:white;border:1px solid #d9e2dc;padding:16px;margin-bottom:20px}.data{width:100%;border-collapse:collapse;background:white;font-size:10px;table-layout:fixed}" +
                ".data th,.data td{padding:9px 7px;border:1px solid #dfe6e1;text-align:left;vertical-align:top;word-wrap:break-word}.data th{background:#e9efe9;color:#44574c}" +
                "small{display:block;color:#718077;margin-top:5px}code{word-break:break-all}pre{white-space:pre-wrap;word-wrap:break-word;margin:8px 0 0;padding:8px;background:#f0f3ef;border-left:3px solid #78a921;font:8px/1.45 monospace}" +
                ".severity{font-weight:700}.critical,.high{color:#c83232}.medium{color:#a46400}.low,.info{color:#167c6d}.false-positive,.ai-review{color:#a46400}.ai-cell{background:#fffaf0}.ai-cell b{display:block;margin-bottom:3px}.ai-cell small{margin:2px 0 7px;line-height:1.45}.foot{margin-top:18px;color:#718077;font-size:9px}" +
                "tr{page-break-inside:avoid}</style></head><body><header class=\"cover\"><div class=\"brand\">CODESENTRY / REMEDIATION REPORT</div>" +
                "<h1>代码安全扫描整改报告</h1><div class=\"meta\">报告编号 CSR-" + String.format("%06d", run.getId()) +
                " · 项目 " + escape(project.getProjectName()) + " · 生成时间 " + TIME.format(LocalDateTime.now()) + "</div></header><main class=\"wrap\">" +
                "<table class=\"summary-table\"><tr>" + summaryCell("扫描模式", scanModeLabel(run)) + summaryCell("当前问题", String.valueOf(data.findings.size())) +
                summaryCell("新增问题", String.valueOf(data.newFindingCount)) + summaryCell("已修复", String.valueOf(data.fixedFindingCount)) +
                "</tr></table>" +
                "<section class=\"card\"><b>扫描基线</b><p>规则集：" + escape(ruleProfileLabel(run.getRuleProfile())) + "</p><p>扫描分支：" +
                escape(value(run.getScanRef(), "当前工作区")) + "；基线分支：" + escape(value(run.getBaselineRef(), "无")) +
                "；扫描提交：" + escape(value(run.getScanCommit(), "-")) + "；merge-base：" + escape(value(run.getMergeBaseCommit(), "-")) +
                "</p></section><p class=\"foot\">人工复核状态：" + escape(data.reviewStatus()) +
                "报告保留人工已确认的风险，以及工具复核完成、通过可信度门槛且未判为误报的问题；人工判为误报的不纳入报告，可信度不代表漏洞可利用性。" +
                "本报告由 CodeSentry 本地节点生成。</p><h2>问题明细与整改建议</h2><table class=\"data\"><thead><tr><th style=\"width:3%\">#</th><th style=\"width:6%\">等级</th>" +
                "<th style=\"width:18%\">问题 / 规则 / 代码片段</th><th style=\"width:14%\">位置</th><th style=\"width:6%\">变化</th><th style=\"width:8%\">人工结论</th><th style=\"width:15%\">利用条件 / 复核证据</th><th style=\"width:16%\">工具复核</th><th style=\"width:14%\">整改建议</th>" +
                "</tr></thead><tbody>" + (rows.length() == 0 ? "<tr><td colspan=\"9\">当前没有符合报告筛选条件的问题，不代表原始扫描未发现风险。</td></tr>" : rows) +
                "</tbody></table></main></body></html>";
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
    private String escape(String value) { return HtmlUtils.htmlEscape(value == null ? "" : value); }
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

    private static class ReportData {
        private final ScanRun run;
        private final ScanProject project;
        private final List<FindingView> findings;
        private final int newFindingCount;
        private final int fixedFindingCount;
        private final Map<Long, AiReviewSummary> aiReviews;

        private ReportData(ScanRun run, ScanProject project, List<FindingView> findings,
                           Map<Long, AiReviewSummary> aiReviews) {
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
