package io.codesentry.ai.mapper;

import io.codesentry.ai.model.AiReviewJob;
import io.codesentry.ai.model.AiReviewRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AiReviewMapper {

    @Insert("INSERT INTO scan_ai_review_job(concurrency, scan_run_id, provider, model, status, total_findings, " +
            "completed_findings, failed_findings, created_at) VALUES(#{concurrency}, #{scanRunId}, #{provider}, #{model}, " +
            "#{status}, #{totalFindings}, #{completedFindings}, #{failedFindings}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertJob(AiReviewJob job);

    @Update("UPDATE scan_ai_review_job SET status = 'RUNNING', started_at = #{startedAt} WHERE id = #{id} AND status = 'PENDING'")
    int markJobRunning(@Param("id") Long id, @Param("startedAt") LocalDateTime startedAt);

    @Update("UPDATE scan_ai_review_job SET completed_findings = #{completed}, failed_findings = #{failed} " +
            "WHERE id = #{id} AND status = 'RUNNING'")
    int updateJobProgress(@Param("id") Long id, @Param("completed") int completed, @Param("failed") int failed);

    @Update("UPDATE scan_ai_review_job SET status = #{status}, error_message = #{errorMessage}, " +
            "finished_at = #{finishedAt} WHERE id = #{id} AND status IN ('PENDING','RUNNING')")
    int finishJob(@Param("id") Long id, @Param("status") String status,
                  @Param("errorMessage") String errorMessage, @Param("finishedAt") LocalDateTime finishedAt);

    @Select("SELECT * FROM scan_ai_review_job WHERE id = #{id}")
    AiReviewJob findJobById(@Param("id") Long id);

    @Update("UPDATE scan_ai_review_job SET status = 'CANCELLED', error_message = '用户停止 AI 审核', " +
            "finished_at = #{time} WHERE id = #{id} AND status IN ('PENDING','RUNNING')")
    int cancelJob(@Param("id") Long id, @Param("time") LocalDateTime time);

    @Select("SELECT * FROM scan_ai_review_job WHERE scan_run_id = #{scanRunId} " +
            "ORDER BY created_at DESC, id DESC LIMIT 1")
    AiReviewJob findLatestJobByRunId(@Param("scanRunId") Long scanRunId);

    @Insert("INSERT INTO scan_ai_review(project_id, scan_run_id, finding_id, review_job_id, review_round, " +
            "provider, model, verdict, confidence, reason, evidence_lines, missing_evidence, recommendation, " +
            "input_hash, prompt_version, status, error_message, created_at) VALUES(#{projectId}, #{scanRunId}, " +
            "#{findingId}, #{reviewJobId}, #{reviewRound}, #{provider}, #{model}, #{verdict}, #{confidence}, " +
            "#{reason}, #{evidenceLines}, #{missingEvidence}, #{recommendation}, #{inputHash}, #{promptVersion}, " +
            "#{status}, #{errorMessage}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertReview(AiReviewRecord review);

    @Select("SELECT * FROM scan_ai_review WHERE finding_id = #{findingId} ORDER BY created_at DESC, id DESC")
    List<AiReviewRecord> findByFindingId(@Param("findingId") Long findingId);

    @Select("SELECT * FROM scan_ai_review WHERE scan_run_id = #{scanRunId} ORDER BY finding_id, created_at DESC, id DESC")
    List<AiReviewRecord> findByRunId(@Param("scanRunId") Long scanRunId);

    @Select("SELECT * FROM scan_ai_review WHERE review_job_id = #{jobId} ORDER BY finding_id, created_at, id")
    List<AiReviewRecord> findByJobId(@Param("jobId") Long jobId);
}
