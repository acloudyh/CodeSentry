package io.codesentry.finding.mapper;

import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.model.ScanFinding;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface ScanFindingMapper {

    @org.apache.ibatis.annotations.SelectProvider(type=ClassifiedSql.class,method="recent")
    List<FindingView> findClassifiedRecent(@Param("limit") int limit,@Param("issueType") String issueType);
    class ClassifiedSql {
        public String recent(){return "SELECT f.*,i.id instance_id,i.scan_run_id,i.file_path,i.start_line,i.end_line,i.code_snippet,i.result_status,t.triage_status,t.comment triage_comment,t.reviewer,t.expires_at FROM scan_finding_instance i JOIN scan_finding f ON f.id=i.finding_id LEFT JOIN scan_triage t ON t.finding_id=f.id AND t.project_id=f.project_id WHERE i.id=(SELECT i2.id FROM scan_finding_instance i2 WHERE i2.finding_id=i.finding_id ORDER BY i2.created_at DESC,i2.id DESC LIMIT 1) AND ("+io.codesentry.finding.service.FindingClassification.sql("f")+")=#{issueType} ORDER BY i.created_at DESC LIMIT #{limit}";}
    }

    @Insert("INSERT INTO scan_finding(project_id, fingerprint, scanner, rule_id, category, severity, title, " +
            "description, recommendation, cwe, first_seen_at, last_seen_at) VALUES(#{projectId}, #{fingerprint}, " +
            "#{scanner}, #{ruleId}, #{category}, #{severity}, #{title}, #{description}, #{recommendation}, #{cwe}, " +
            "#{firstSeenAt}, #{lastSeenAt}) ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), " +
            "severity = VALUES(severity), title = VALUES(title), description = VALUES(description), " +
            "recommendation = VALUES(recommendation), last_seen_at = VALUES(last_seen_at)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(ScanFinding finding);

    @Insert("INSERT INTO scan_finding_instance(finding_id, scan_run_id, file_path, start_line, end_line, " +
            "code_snippet, result_status, created_at) VALUES(#{findingId}, #{scanRunId}, #{filePath}, #{startLine}, " +
            "#{endLine}, #{codeSnippet}, #{resultStatus}, #{createdAt}) " +
            "ON DUPLICATE KEY UPDATE file_path = VALUES(file_path), start_line = VALUES(start_line), " +
            "end_line = VALUES(end_line), code_snippet = VALUES(code_snippet), result_status = VALUES(result_status)")
    int insertInstance(@Param("findingId") Long findingId,
                       @Param("scanRunId") Long scanRunId,
                       @Param("filePath") String filePath,
                       @Param("startLine") Integer startLine,
                       @Param("endLine") Integer endLine,
                       @Param("codeSnippet") String codeSnippet,
                       @Param("resultStatus") String resultStatus,
                       @Param("createdAt") LocalDateTime createdAt);

    /** 仅加载报告采纳判断及去重所需字段，不读取大段证据或源码。 */
    @Select("SELECT f.id,f.project_id,f.scanner,f.rule_id,f.category,i.scan_run_id,i.file_path,i.start_line,i.end_line,i.result_status,t.triage_status, " +
            "CASE WHEN LOWER(f.scanner)='trivy' AND UPPER(f.category)='VULNERABILITY' " +
            "THEN SUBSTRING_INDEX(i.code_snippet,CHAR(10),1) ELSE NULL END code_snippet " +
            "FROM scan_finding_instance i JOIN scan_finding f ON f.id=i.finding_id " +
            "LEFT JOIN scan_triage t ON t.finding_id=f.id AND t.project_id=f.project_id " +
            "WHERE i.scan_run_id=#{scanRunId} ORDER BY FIELD(f.severity,'CRITICAL','HIGH','MEDIUM','LOW','INFO'),i.file_path,i.start_line")
    List<FindingView> findPolicyByRunId(@Param("scanRunId") Long scanRunId);

    @Select("SELECT f.*, i.id AS instance_id, i.scan_run_id, i.file_path, i.start_line, i.end_line, " +
            "i.code_snippet, i.result_status, t.triage_status, t.comment AS triage_comment, t.reviewer, " +
            "t.expires_at FROM scan_finding_instance i JOIN scan_finding f ON f.id = i.finding_id " +
            "LEFT JOIN scan_triage t ON t.finding_id = f.id AND t.project_id = f.project_id " +
            "WHERE i.scan_run_id = #{scanRunId} ORDER BY FIELD(f.severity, 'CRITICAL','HIGH','MEDIUM','LOW','INFO'), " +
            "i.file_path, i.start_line")
    List<FindingView> findByRunId(@Param("scanRunId") Long scanRunId);

    @Select("SELECT f.*, i.id AS instance_id, i.scan_run_id, i.file_path, i.start_line, i.end_line, " +
            "i.code_snippet, i.result_status, t.triage_status, t.comment AS triage_comment, t.reviewer, " +
            "t.expires_at FROM scan_finding_instance i JOIN scan_finding f ON f.id = i.finding_id " +
            "LEFT JOIN scan_triage t ON t.finding_id = f.id AND t.project_id = f.project_id " +
            "WHERE i.id = (SELECT i2.id FROM scan_finding_instance i2 WHERE i2.finding_id = i.finding_id " +
            "ORDER BY i2.created_at DESC, i2.id DESC LIMIT 1) " +
            "ORDER BY i.created_at DESC LIMIT #{limit}")
    List<FindingView> findRecent(@Param("limit") int limit);

    @Select("SELECT f.*, i.id AS instance_id, i.scan_run_id, i.file_path, i.start_line, i.end_line, " +
            "i.code_snippet, i.result_status, t.triage_status, t.comment AS triage_comment, t.reviewer, " +
            "t.expires_at FROM scan_finding_instance i JOIN scan_finding f ON f.id = i.finding_id " +
            "LEFT JOIN scan_triage t ON t.finding_id = f.id AND t.project_id = f.project_id " +
            "WHERE f.id = #{findingId} ORDER BY i.created_at DESC LIMIT 1")
    FindingView findLatestByFindingId(@Param("findingId") Long findingId);

    @Insert("INSERT INTO scan_triage(project_id, finding_id, triage_status, comment, reviewer, expires_at, " +
            "created_at, updated_at) VALUES(#{projectId}, #{findingId}, #{triageStatus}, #{comment}, " +
            "#{reviewer}, #{expiresAt}, NOW(3), NOW(3)) ON DUPLICATE KEY UPDATE " +
            "triage_status = VALUES(triage_status), comment = VALUES(comment), reviewer = VALUES(reviewer), " +
            "expires_at = VALUES(expires_at), updated_at = NOW(3)")
    int upsertTriage(@Param("projectId") Long projectId,
                     @Param("findingId") Long findingId,
                     @Param("triageStatus") String triageStatus,
                     @Param("comment") String comment,
                     @Param("reviewer") String reviewer,
                     @Param("expiresAt") LocalDateTime expiresAt);
}
