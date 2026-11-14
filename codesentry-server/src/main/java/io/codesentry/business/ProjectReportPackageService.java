package io.codesentry.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CancellableTasks;
import io.codesentry.project.model.ScanProject;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.report.service.RemediationReportService;
import io.codesentry.report.service.ReportFileNames;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 固定项目轮次后在后台串行生成各资产报告；产物落盘，轮询或下载不重新生成。 */
@Service
public class ProjectReportPackageService {
    public static class Job {
        public String id, format, status, fileName, errorMessage;
        public Long businessProjectId, roundId;
        public int totalFiles, completedFiles;
        public long fileBytes;
        public LocalDateTime createdAt, finishedAt;
    }
    private final JdbcTemplate db;
    private final BusinessProjectService projects;
    private final RemediationReportService reports;
    private final ObjectMapper json;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;
    private final Path directory;
    private final AtomicBoolean executing = new AtomicBoolean();
    @Value("${codesentry.runtime.worker-enabled:true}") private boolean workerEnabled = true;
    private volatile boolean ready;

    public ProjectReportPackageService(JdbcTemplate db, BusinessProjectService projects, RemediationReportService reports,
            ObjectMapper json, CodeSentryProperties properties, @Qualifier("reportPackageTaskExecutor") TaskExecutor executor,
            PlatformTransactionManager manager) {
        this.db=db; this.projects=projects; this.reports=reports; this.json=json; this.executor=executor;
        this.tx=new TransactionTemplate(manager);
        // 排队等待项目锁后必须看到前一请求提交的任务，避免可重复读旧快照绕过幂等检查。
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        directory=properties.getStorage().rootPath().resolve(properties.getStorage().getReportsDirectory()).resolve("project-packages");
    }

    /** 提交短事务仅保存范围；同一请求重放返回原任务，重复点击活动任务不会重复生成文件。 */
    public Job start(Long projectId, Long roundId, String format, String requestKey) {
        if (!workerEnabled) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"当前实例不执行后台打包任务");
        if (!Set.of("PDF","WORD","BOTH").contains(format == null ? "" : format)) throw BusinessProjectService.bad("请选择仅 PDF、仅 Word 或 Word＋PDF");
        if (requestKey == null || requestKey.isBlank() || requestKey.length()>80) throw BusinessProjectService.bad("打包请求标识无效");
        return tx.execute(status -> {
            projects.require(projectId);
            db.queryForObject("SELECT id FROM business_project WHERE id=? FOR UPDATE",Long.class,projectId);
            List<Job> previous=db.query("SELECT * FROM project_report_package WHERE business_project_id=? AND request_key=?",this::map,projectId,requestKey);
            if (!previous.isEmpty()) {
                Job job=previous.get(0);
                if (!job.roundId.equals(roundId) || !job.format.equals(format)) throw BusinessProjectService.conflict("请求标识已用于其他轮次或格式，请重新提交");
                return job;
            }
            ProjectScanRound scope=projects.round(projectId,roundId);
            if (scope.assetHistory || roundId<=0) throw BusinessProjectService.bad("请先选择项目扫描轮次，再打包各资产报告");
            if ("RUNNING".equals(scope.status)) throw BusinessProjectService.conflict("该轮扫描尚未结束，请结束后打包报告");
            int count=(int)scope.items.stream().filter(this::eligible).count();
            if (count==0) throw BusinessProjectService.bad("本轮没有已完成或部分完成的资产报告可打包");
            List<Job> active=db.query("SELECT * FROM project_report_package WHERE business_project_id=? AND round_id=? AND report_format=? AND status IN ('PENDING','RUNNING') ORDER BY created_at DESC LIMIT 1",this::map,projectId,roundId,format);
            if (!active.isEmpty()) return active.get(0);
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO project_report_package(id,business_project_id,round_id,request_key,report_format,status,file_name,scope_json,total_files,created_at) VALUES(?,?,?,?,?,'PENDING',?,?,?,NOW(3))",
                    id,projectId,roundId,requestKey,format,ReportFileNames.securityScan(scope.projectName,"zip"),write(scope),count*("BOTH".equals(format)?2:1));
            return require(projectId,id);
        });
    }

    /** 离开页面后仍按项目和轮次恢复最后一次该格式的任务。 */
    public Job latest(Long projectId, Long roundId, String format) {
        projects.round(projectId,roundId);
        List<Job> jobs=db.query("SELECT * FROM project_report_package WHERE business_project_id=? AND round_id=? AND report_format=? ORDER BY created_at DESC,id DESC LIMIT 1",this::map,projectId,roundId,format);
        return jobs.isEmpty()?null:jobs.get(0);
    }

    public Job require(Long projectId, String id) {
        List<Job> jobs=db.query("SELECT * FROM project_report_package WHERE business_project_id=? AND id=?",this::map,projectId,id);
        if (jobs.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND,"项目报告打包任务不存在");
        return jobs.get(0);
    }

    /** 下载只暴露已经完整生成的 ZIP，路径由服务端 UUID 构造，不接受客户端路径。 */
    public Path download(Long projectId, String id) {
        Job job=require(projectId,id);
        if (!"COMPLETED".equals(job.status)) throw BusinessProjectService.conflict("ZIP 尚未生成完成，请查看打包进度");
        Path file=file(id,".zip");
        if (!Files.isRegularFile(file)) throw new BusinessException(HttpStatus.GONE,"ZIP 文件已不存在，请重新生成");
        return file;
    }

    /** 未开始的任务继续排队，执行到一半的任务明确失败，不能提供半个压缩包。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!workerEnabled) return;
        List<String> interrupted=db.queryForList("SELECT id FROM project_report_package WHERE status='RUNNING'",String.class);
        db.update("UPDATE project_report_package SET status='FAILED',error_message='服务中断，打包未完成，请重新生成',finished_at=NOW(3) WHERE status='RUNNING'");
        interrupted.forEach(id -> removePartial(id)); ready=true;
    }

    /** 数据库保存排队状态，独立单工作线程只在空闲时认领一项，避免占用扫描及审核线程。 */
    @Scheduled(fixedDelay=1500)
    public void dispatch() {
        if (!ready || !workerEnabled || !executing.compareAndSet(false,true)) return;
        boolean submitted=false;
        try {
            List<String> queued=db.queryForList("SELECT id FROM project_report_package WHERE status='PENDING' ORDER BY created_at,id LIMIT 1",String.class);
            if (queued.isEmpty()) return;
            String id=queued.get(0);
            if (db.update("UPDATE project_report_package SET status='RUNNING' WHERE id=? AND status='PENDING'",id)!=1) return;
            try { executor.execute(() -> { try { generate(id); } finally { executing.set(false); } }); submitted=true; }
            catch (RuntimeException rejected) { db.update("UPDATE project_report_package SET status='PENDING' WHERE id=? AND status='RUNNING'",id); }
        } finally { if (!submitted) executing.set(false); }
    }

    /** 逐个资产读取同一份报告数据写入 ZIP；全部成功后原子发布，重名在压缩包内追加资产编号。 */
    private void generate(String id) {
        String currentAsset=null;
        try {
            Job job=db.queryForObject("SELECT * FROM project_report_package WHERE id=?",this::map,id);
            ProjectScanRound scope=json.readValue(db.queryForObject("SELECT scope_json FROM project_report_package WHERE id=?",String.class,id),ProjectScanRound.class);
            Files.createDirectories(directory);
            boolean word=!"PDF".equals(job.format), pdf=!"WORD".equals(job.format);
            Set<String> used=new HashSet<>();
            try (ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(file(id,".part")),StandardCharsets.UTF_8)) {
                for (ProjectScanItem item:scope.items) {
                    if (!eligible(item)) continue;
                    CancellableTasks.check();
                    currentAsset=item.assetName;
                    ScanProject asset=new ScanProject();asset.setId(item.assetId);asset.setProjectName(item.assetName);
                    asset.setSourceType(ProjectSourceType.valueOf(item.sourceType));asset.setApplicationType(item.applicationType);
                    asset.setTargetJavaVersion(item.targetJavaVersion);asset.setTargetNodeVersion(item.targetNodeVersion);
                    RemediationReportService.AssetDocuments documents=reports.exportAssetDocuments(item.runId,asset,word,pdf);
                    String name=ReportFileNames.securityScan(item.assetName,"docx");int duplicate=0;
                    while (!used.add(name.toLowerCase(Locale.ROOT))) name=ReportFileNames.securityScan(item.assetName+"（资产"+item.assetId+(duplicate++==0?"":"-"+duplicate)+"）","docx");
                    if (word) { put(zip,(pdf?"Word/":"")+name,documents.word); progress(id); }
                    if (pdf) { put(zip,(word?"PDF/":"")+name.substring(0,name.length()-5)+".pdf",documents.pdf); progress(id); }
                }
                if (!scope.fullScope || scope.items.stream().anyMatch(i -> !"COMPLETED".equals(i.status)) || !scope.unselectedAssets.isEmpty())
                    put(zip,"导出说明.txt",coverage(scope).getBytes(StandardCharsets.UTF_8));
            }
            CancellableTasks.check();
            Files.move(file(id,".part"),file(id,".zip"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            db.update("UPDATE project_report_package SET status='COMPLETED',file_bytes=?,finished_at=NOW(3) WHERE id=?",Files.size(file(id,".zip")),id);
        } catch (Exception error) {
            removePartial(id);
            // 不回传堆栈或磁盘路径；保留已生成计数，用户能明确知道任务未完成。
            String message=(currentAsset==null?"打包初始化":"资产“"+currentAsset+"”报告生成或打包")+"失败（"+error.getClass().getSimpleName()+"），请重试；不会提供不完整 ZIP";
            db.update("UPDATE project_report_package SET status='FAILED',error_message=?,finished_at=NOW(3) WHERE id=?",message,id);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("项目报告 ZIP 生成失败：{}，{}",id,error.getClass().getSimpleName());
        }
    }
    private boolean eligible(ProjectScanItem item) { return item.runId!=null && Set.of("COMPLETED","PARTIAL").contains(item.status==null?"":item.status); }
    private void progress(String id) { db.update("UPDATE project_report_package SET completed_files=completed_files+1 WHERE id=?",id); }
    private void put(ZipOutputStream zip,String name,byte[] content) throws java.io.IOException {
        CancellableTasks.check();
        if (content==null || content.length==0) throw new IllegalStateException("报告内容为空");
        zip.putNextEntry(new ZipEntry(name));zip.write(content);zip.closeEntry();
    }
    private String coverage(ProjectScanRound scope) {
        StringBuilder text=new StringBuilder("项目："+scope.projectName+"\n扫描轮次：#P"+scope.id+"\n范围："+(scope.fullScope?"全部资产":"部分资产")+"\n仅打包本轮已完成或部分完成的资产报告，不使用历史结果补齐。部分完成不代表所有工具检查通过。\n");
        for (ProjectScanItem item:scope.items) text.append(item.assetName).append(" · 扫描 #").append(item.runId==null?"未启动":item.runId).append(" · ").append(item.status).append(eligible(item)?" · 已包含报告":" · 未包含报告").append('\n');
        for (Map<String,Object> asset:scope.unselectedAssets) text.append(asset.get("assetName")).append(" · 本轮未扫描 · 未包含报告\n");
        return text.toString();
    }
    private Path file(String id,String suffix) { return directory.resolve(id+suffix); }
    private void removePartial(String id) { try { Files.deleteIfExists(file(id,".part")); } catch (java.io.IOException ignored) { /* 下次清理可安全识别 .part，下载始终不提供该文件。 */ } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("无法保存打包范围",e); } }
    private Job map(ResultSet r,int row) throws SQLException {
        Job j=new Job();j.id=r.getString("id");j.businessProjectId=r.getLong("business_project_id");j.roundId=r.getLong("round_id");
        j.format=r.getString("report_format");j.status=r.getString("status");j.fileName=r.getString("file_name");j.errorMessage=r.getString("error_message");
        j.totalFiles=r.getInt("total_files");j.completedFiles=r.getInt("completed_files");j.fileBytes=r.getLong("file_bytes");
        j.createdAt=r.getTimestamp("created_at").toLocalDateTime();j.finishedAt=r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toLocalDateTime();return j;
    }
}
