package io.codesentry.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.report.service.RemediationReportService;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.*;

/** 将一个真实项目扫描轮次归档为汇总报告，固定覆盖范围和各资产整改内容。 */
@Service
public class ProjectReportService {
    private final JdbcTemplate db;
    private final BusinessProjectService projects;
    private final RemediationReportService reports;
    private final ObjectMapper json;
    /** 注入项目范围、原资产报告及快照序列化能力。 */
    public ProjectReportService(JdbcTemplate db,BusinessProjectService projects,RemediationReportService reports,ObjectMapper json) { this.db=db;this.projects=projects;this.reports=reports;this.json=json; }
    public static class Snapshot {
        public ProjectScanRound round;
        public String html;
        public List<byte[]> documents=new ArrayList<>();
        public int ledgerCount; public int includedCount;
        public Map<String,Integer> severityCounts=new LinkedHashMap<>();
    }
    /** 按历史视图导出原资产报告，不将单资产记录伪装为整项目报告。 */
    public byte[] assetHistoryReport(Long projectId,Long viewId,String format) {
        ProjectScanRound history=projects.round(projectId,viewId);
        if(!history.assetHistory) throw BusinessProjectService.bad("不是资产历史扫描");
        Long runId=history.items.get(0).runId;
        return "html".equals(format)?reports.exportHtml(runId):"pdf".equals(format)?reports.exportPdf(runId):reports.exportDocx(runId);
    }
    /** 沿用历史资产原报告的下载名称。 */
    public String assetHistoryReportName(Long projectId,Long viewId,String format) {
        projects.round(projectId,viewId);
        return io.codesentry.report.service.ReportFileNames.securityScan(projects.require(projectId).name,format);
    }
    /** 串行化首次归档并保存内容快照；已归档报告直接复用，后续审核或联网补查不改写。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public Snapshot archive(Long projectId,Long roundId) {
        // Serialize first archive and cancel/start actions for this project.
        projects.require(projectId);
        if(roundId<0) throw BusinessProjectService.bad("资产历史扫描使用原始单资产报告入口");
        db.queryForObject("SELECT id FROM business_project WHERE id=? FOR UPDATE",Long.class,projectId);
        projects.round(projectId,roundId);
        List<String> existing=db.queryForList("SELECT content_json FROM project_report_snapshot WHERE round_id=?",String.class,roundId);
        try {
            // 归档后直接复用快照；后续审核、联网补查或资产改名不能改写已交付的汇总报告。
            if(!existing.isEmpty()) return json.readValue(existing.get(0),Snapshot.class);
            Snapshot snapshot=new Snapshot(); snapshot.round=projects.round(projectId,roundId);
            if("RUNNING".equals(snapshot.round.status)) throw BusinessProjectService.conflict("该轮扫描尚未结束，请结束扫描后导出项目报告");
            StringBuilder sections=new StringBuilder(); String styles="";
            for(ProjectScanItem item:snapshot.round.items) {
                // 失败、取消和未扫描项只列覆盖状态，不借用旧扫描生成貌似完整的整改明细。
                if(!List.of("COMPLETED","PARTIAL").contains(item.status)) continue;
                io.codesentry.project.model.ScanProject asset=new io.codesentry.project.model.ScanProject();
                asset.setId(item.assetId); asset.setProjectName(item.assetName); asset.setSourceType(io.codesentry.project.model.ProjectSourceType.valueOf(item.sourceType)); asset.setTargetJavaVersion(item.targetJavaVersion); asset.setApplicationType(item.applicationType); asset.setTargetNodeVersion(item.targetNodeVersion);
                String child=reports.projectSectionHtml(item.runId,asset);
                if(styles.isEmpty()) styles=child.substring(child.indexOf("<style>")+7,child.indexOf("</style>"));
                snapshot.documents.add(reports.exportProjectDocx(item.runId,asset));
                java.util.List<io.codesentry.finding.model.FindingView> included=reports.archiveFindings(item.runId);
                snapshot.includedCount+=included.size();
                for(io.codesentry.finding.model.FindingView finding:included) snapshot.severityCounts.merge(finding.getSeverity(),1,Integer::sum);
                snapshot.ledgerCount+=db.queryForObject("SELECT COUNT(*) FROM scan_finding_instance WHERE scan_run_id=?",Integer.class,item.runId);
                sections.append("<section class=\"asset-section\"><h1>").append(escape(item.assetName)).append(" · 扫描 #").append(item.runId).append("</h1>").append(child.substring(child.indexOf("<body>")+6,child.lastIndexOf("</body>"))).append("</section>");
            }
            snapshot.html="<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\"/><title>项目整改报告</title><style>"+styles+"body{font-family:'CodeSentrySong',serif;padding:20px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;padding:7px;text-align:left}.asset-section{page-break-before:always}h1,h2{font-family:'CodeSentryHei',sans-serif}td{word-wrap:break-word}</style></head><body>"+summaryHtml(snapshot)+sections+"</body></html>";
            db.update("INSERT INTO project_report_snapshot(round_id,content_json,created_at) VALUES(?,?,NOW(3))",roundId,json.writeValueAsString(snapshot));
            return snapshot;
        } catch(io.codesentry.common.error.BusinessException e) { throw e; }
        catch(Exception e) { throw new IllegalStateException("项目报告归档失败",e); }
    }
    /** 返回已归档的项目 HTML 内容。 */
    public byte[] html(Snapshot s) { return s.html.getBytes(StandardCharsets.UTF_8); }
    /** 使用统一中文字体渲染已归档项目 HTML。 */
    public byte[] pdf(Snapshot s) { return reports.renderPdfHtml(s.html); }
    /** 合并归档时固定的子文档，保留各资产问题及原始来源编号。 */
    public byte[] docx(Snapshot s) {
        try(XWPFDocument doc=new XWPFDocument(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr sect=doc.getDocument().getBody().addNewSectPr();
            var page=sect.addNewPgSz();page.setW(BigInteger.valueOf(11906));page.setH(BigInteger.valueOf(16838));
            var margin=sect.addNewPgMar();margin.setTop(BigInteger.valueOf(1080));margin.setBottom(BigInteger.valueOf(1080));margin.setLeft(BigInteger.valueOf(1080));margin.setRight(BigInteger.valueOf(1080));
            paragraph(doc,s.round.projectName+" · 项目安全扫描整改报告",20);
            paragraph(doc,summaryText(s),11);
            XWPFTable table=doc.createTable(1,5); String[] headers={"资产","来源扫描","状态","扫描版本 / 文件","异常说明"}; for(int i=0;i<5;i++) table.getRow(0).getCell(i).setText(headers[i]);
            for(ProjectScanItem item:s.round.items) { XWPFTableRow row=table.createRow();String[] cells={item.assetName,String.valueOf(item.runId==null?"未启动":item.runId),item.status,version(item),item.errorMessage==null?"":item.errorMessage}; for(int i=0;i<5;i++) row.getCell(i).setText(cells[i]); }
            for(Map<String,Object> asset:s.round.unselectedAssets) { XWPFTableRow row=table.createRow();row.getCell(0).setText(String.valueOf(asset.get("assetName")));row.getCell(1).setText("—");row.getCell(2).setText("本轮未扫描"); }
            paragraph(doc,"未选中资产："+Math.max(0,s.round.assetCount-s.round.items.size())+" 个。失败、取消、未启动及工具部分失败的资产不代表已完整检查通过。",11);
            int index=0;
            for(ProjectScanItem item:s.round.items) if(List.of("COMPLETED","PARTIAL").contains(item.status)) {
                XWPFParagraph title=doc.createParagraph();title.setPageBreak(true);XWPFRun run=title.createRun();run.setText(item.assetName+" · 扫描 #"+item.runId);run.setFontFamily("黑体");run.setFontSize(16);
                try(XWPFDocument child=new XWPFDocument(new ByteArrayInputStream(s.documents.get(index++)))) {
                    for(IBodyElement element:child.getBodyElements()) {
                        if(element instanceof XWPFParagraph) doc.createParagraph().getCTP().set(((XWPFParagraph)element).getCTP());
                        else if(element instanceof XWPFTable) doc.createTable().getCTTbl().set(((XWPFTable)element).getCTTbl());
                    }
                }
            }
            reports.embedWordFonts(doc);
            doc.write(out);return out.toByteArray();
        } catch(Exception e) { throw new IllegalStateException("项目 Word 报告生成失败",e); }
    }
    /** 以指定字号写入宋体段落，保持项目汇总文档的中文排版。 */
    private void paragraph(XWPFDocument doc,String text,int size) { XWPFRun r=doc.createParagraph().createRun();r.setText(text);r.setFontFamily("宋体");r.setFontSize(size); }
    /** 汇总本轮覆盖、状态、台账及采纳数量，并明确部分扫描范围。 */
    private String summaryText(Snapshot s) { return "项目扫描轮次 #P"+s.round.id+" · "+(s.round.fullScope?"全部资产扫描":"部分资产扫描 / 失败项重试")+" · 扫描时间 "+s.round.createdAt+" · 覆盖 "+s.round.items.size()+" / "+s.round.assetCount+" 个资产 · 完整完成 "+s.round.completedCount+" · 非完整完成 "+s.round.failedCount+" · 台账 "+s.ledgerCount+" 条 / 纳入整改 "+s.includedCount+" 条 / 未纳入 "+(s.ledgerCount-s.includedCount)+" 条 · 纳入风险：严重 "+s.severityCounts.getOrDefault("CRITICAL",0)+" / 高危 "+s.severityCounts.getOrDefault("HIGH",0)+" / 中危 "+s.severityCounts.getOrDefault("MEDIUM",0)+" / 低危 "+s.severityCounts.getOrDefault("LOW",0)+" / 提示 "+s.severityCounts.getOrDefault("INFO",0); }
    /** 生成项目报告覆盖与风险统计区，输出内容进行 HTML 转义。 */
    private String summaryHtml(Snapshot s) {
        StringBuilder h=new StringBuilder("<h1>"+escape(s.round.projectName)+" · 项目安全扫描整改报告</h1><p>"+escape(summaryText(s))+"</p><table><thead><tr><th>资产</th><th>来源扫描</th><th>状态</th><th>扫描版本 / 文件</th><th>异常说明</th></tr></thead><tbody>");
        for(ProjectScanItem i:s.round.items) h.append("<tr><td>").append(escape(i.assetName)).append("</td><td>").append(i.runId==null?"未启动":i.runId).append("</td><td>").append(escape(i.status)).append("</td><td>").append(escape(version(i))).append("</td><td>").append(escape(i.errorMessage)).append("</td></tr>");
        for(Map<String,Object> asset:s.round.unselectedAssets) h.append("<tr><td>").append(escape(String.valueOf(asset.get("assetName")))).append("</td><td>—</td><td>本轮未扫描</td><td>—</td><td>不在本轮扫描范围内</td></tr>");
        return h.append("</tbody></table><p>未选中资产：").append(Math.max(0,s.round.assetCount-s.round.items.size())).append(" 个。失败、取消、未启动及工具部分失败的资产不代表已完整检查通过。</p>").toString();
    }
    /** 描述本次扫描实际提交或工作区标识，不取远端后续版本。 */
    private String version(ProjectScanItem i) { return i.run!=null && i.run.getScanCommit()!=null?i.run.getScanCommit()+(Boolean.TRUE.equals(i.run.getRepositoryDirty())?"（包含未提交修改）":""):i.scanRef==null?"当前工作区":i.scanRef; }
    static String escape(String value) { return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
}
