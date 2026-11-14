package io.codesentry.scan.mapper;

import io.codesentry.scan.model.ScanToolRun;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ScanToolRunMapper {

  @Insert(
      "INSERT INTO scan_tool_run(scan_run_id, tool_name, phase, status, started_at) "
          + "VALUES(#{scanRunId}, #{toolName}, #{phase}, #{status}, #{startedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(ScanToolRun toolRun);

  @Update(
      "UPDATE scan_tool_run SET command_line = #{commandLine}, working_directory = #{workingDirectory}, "
          + "output_log = #{outputLog} WHERE id = #{id} AND status = 'RUNNING'")
  int updateRunningLog(ScanToolRun toolRun);

  @Update(
      "UPDATE scan_tool_run SET status = #{status}, exit_code = #{exitCode}, "
          + "command_line = #{commandLine}, working_directory = #{workingDirectory}, "
          + "raw_report_path = #{rawReportPath}, output_log = #{outputLog}, "
          + "error_message = #{errorMessage}, finished_at = #{finishedAt} WHERE id = #{id} AND status <> 'CANCELLED'")
  int finish(ScanToolRun toolRun);

  @Select(
      "SELECT * FROM scan_tool_run WHERE scan_run_id = #{scanRunId} ORDER BY started_at ASC, id ASC")
  List<ScanToolRun> findByScanRunId(@Param("scanRunId") Long scanRunId);

  @Update(
      "UPDATE scan_tool_run SET status = 'CANCELLED', error_message = '用户停止扫描', "
          + "finished_at = NOW() WHERE scan_run_id = #{id} AND status = 'RUNNING'")
  int cancelRunning(@Param("id") Long id);
}
