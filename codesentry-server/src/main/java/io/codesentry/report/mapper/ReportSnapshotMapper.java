package io.codesentry.report.mapper;
import org.apache.ibatis.annotations.*;
public interface ReportSnapshotMapper {
    @Select("SELECT findings_json FROM remediation_report_snapshot WHERE scan_run_id = #{id}")
    String find(@Param("id") Long id);
    @Insert("INSERT IGNORE INTO remediation_report_snapshot(scan_run_id, findings_json, created_at) VALUES(#{id}, #{json}, NOW(3))")
    int insert(@Param("id") Long id, @Param("json") String json);
}
