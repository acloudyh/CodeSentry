package io.codesentry.scan.mapper;

import io.codesentry.scan.model.ScanRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ScanRunMapper {

    @Insert("INSERT INTO scan_run(network_mode, remediation_status, artifact_path, source_report_run_id, project_id, scan_mode, workspace_mode, scan_ref, baseline_ref, rule_profile, " +
            "repository_dirty, status, total_findings, new_findings, fixed_findings, created_at) " +
            "VALUES(#{networkMode}, #{remediationStatus}, #{artifactPath}, #{sourceReportRunId}, #{projectId}, #{scanMode}, #{workspaceMode}, #{scanRef}, #{baselineRef}, #{ruleProfile}, " +
            "#{repositoryDirty}, #{status}, #{totalFindings}, #{newFindings}, #{fixedFindings}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ScanRun scanRun);

    @Update("UPDATE scan_run SET remediation_status = #{remediationStatus}, remediation_message = #{remediationMessage}, " +
            "remediation_evidence = #{remediationEvidence} WHERE id = #{id} AND (status IN ('PENDING','PREPARING','RUNNING') OR (status IN ('COMPLETED','PARTIAL') AND remediation_status IN ('PENDING','RUNNING')))")
    int updateRemediation(ScanRun run);

    @Update("UPDATE scan_run SET remediation_status='PENDING',remediation_message='等待后台补充联网核实' WHERE id=#{id} AND network_mode='ONLINE' AND status IN ('COMPLETED','PARTIAL') AND (remediation_status IS NULL OR remediation_status NOT IN ('PENDING','RUNNING'))")
    int claimRemediationRefresh(@Param("id") Long id);

    @Update("UPDATE scan_run SET remediation_status='FAILED',remediation_message=#{message} WHERE id=#{id} AND status IN ('COMPLETED','PARTIAL') AND remediation_status IN ('PENDING','RUNNING')")
    int failRemediationRefresh(@Param("id") Long id,@Param("message") String message);

    @Update("UPDATE scan_run SET remediation_status='FAILED',remediation_message='服务重启，联网补查中断，可重新补查' WHERE status IN ('COMPLETED','PARTIAL') AND remediation_status IN ('PENDING','RUNNING')")
    int recoverRemediationRefresh();

    @Select("SELECT * FROM scan_run WHERE source_report_run_id = #{id} ORDER BY id DESC LIMIT 100")
    List<ScanRun> findAcceptanceRuns(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM remediation_report_snapshot WHERE scan_run_id = #{id}")
    int hasReportSnapshot(@Param("id") Long id);

    @Select("SELECT * FROM scan_run WHERE id = #{id}")
    ScanRun findById(@Param("id") Long id);

    @Select({"<script>", "SELECT s.* FROM scan_run s WHERE s.status IN ('COMPLETED','PARTIAL')",
            "<if test='assetId != null'> AND s.project_id=#{assetId}</if>",
            "<if test='businessProjectId != null'> AND EXISTS(SELECT 1 FROM business_project_asset a WHERE a.asset_id=s.project_id AND a.business_project_id=#{businessProjectId})</if>",
            "ORDER BY s.created_at DESC,s.id DESC LIMIT #{limit}", "</script>"})
    List<ScanRun> findReportRuns(@Param("limit") int limit,@Param("businessProjectId") Long businessProjectId,@Param("assetId") Long assetId);

    @Select("SELECT * FROM scan_run ORDER BY created_at DESC LIMIT #{limit}")
    List<ScanRun> findRecent(@Param("limit") int limit);

    @Update("UPDATE scan_run SET status = #{status}, started_at = #{startedAt} WHERE id = #{id} AND status = 'PENDING'")
    int markStarted(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("startedAt") LocalDateTime startedAt);

    @Update("UPDATE scan_run SET scan_commit = #{scanCommit}, baseline_head_commit = #{baselineHeadCommit}, " +
            "merge_base_commit = #{mergeBaseCommit}, repository_dirty = #{repositoryDirty}, " +
            "status = #{status} WHERE id = #{id} AND status = 'PREPARING'")
    int updatePrepared(ScanRun scanRun);

    @Update("UPDATE scan_run SET status = #{status}, total_findings = #{totalFindings}, " +
            "new_findings = #{newFindings}, fixed_findings = #{fixedFindings}, " +
            "error_message = #{errorMessage}, finished_at = #{finishedAt} WHERE id = #{id} AND status IN ('PENDING','PREPARING','RUNNING')")
    int finish(ScanRun scanRun);

    @Update("UPDATE scan_run SET remediation_message = CASE WHEN network_mode = 'ONLINE' AND remediation_status IN ('PENDING','RUNNING') " +
            "THEN '用户停止扫描，联网核实未完成' ELSE remediation_message END, " +
            "remediation_status = CASE WHEN network_mode = 'ONLINE' AND remediation_status IN ('PENDING','RUNNING') " +
            "THEN 'CANCELLED' ELSE remediation_status END, status = 'CANCELLED', error_message = '用户停止扫描，结果不完整', " +
            "finished_at = #{time} WHERE id = #{id} AND (status IN ('PENDING','PREPARING','RUNNING') " +
            "OR (status IN ('COMPLETED','PARTIAL') AND finished_at >= DATE_SUB(#{time}, INTERVAL 10 SECOND)))")
    int cancel(@Param("id") Long id, @Param("time") LocalDateTime time);
}
