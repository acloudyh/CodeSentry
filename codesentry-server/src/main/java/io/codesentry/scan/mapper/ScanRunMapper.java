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

    @Insert("INSERT INTO scan_run(project_id, scan_mode, workspace_mode, scan_ref, baseline_ref, rule_profile, " +
            "repository_dirty, status, total_findings, new_findings, fixed_findings, created_at) " +
            "VALUES(#{projectId}, #{scanMode}, #{workspaceMode}, #{scanRef}, #{baselineRef}, #{ruleProfile}, " +
            "#{repositoryDirty}, #{status}, #{totalFindings}, #{newFindings}, #{fixedFindings}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ScanRun scanRun);

    @Select("SELECT * FROM scan_run WHERE id = #{id}")
    ScanRun findById(@Param("id") Long id);

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

    @Update("UPDATE scan_run SET status = 'CANCELLED', error_message = '用户停止扫描，结果不完整', " +
            "finished_at = #{time} WHERE id = #{id} AND (status IN ('PENDING','PREPARING','RUNNING') " +
            "OR (status IN ('COMPLETED','PARTIAL') AND finished_at >= DATE_SUB(#{time}, INTERVAL 10 SECOND)))")
    int cancel(@Param("id") Long id, @Param("time") LocalDateTime time);
}
