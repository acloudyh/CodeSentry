package io.codesentry.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

@Component
public class DatabaseCompatibilityInitializer implements ApplicationRunner {

    private final DataSource dataSource;

    public DatabaseCompatibilityInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureColumn("scan_tool_run", "phase",
                "ALTER TABLE scan_tool_run ADD COLUMN phase VARCHAR(32) NOT NULL DEFAULT 'current' AFTER tool_name");
        ensureColumn("scan_tool_run", "command_line",
                "ALTER TABLE scan_tool_run ADD COLUMN command_line VARCHAR(2048) NULL AFTER exit_code");
        ensureColumn("scan_tool_run", "working_directory",
                "ALTER TABLE scan_tool_run ADD COLUMN working_directory VARCHAR(1024) NULL AFTER command_line");
        ensureColumn("scan_tool_run", "output_log",
                "ALTER TABLE scan_tool_run ADD COLUMN output_log LONGTEXT NULL AFTER raw_report_path");
        ensureColumn("scan_run", "rule_profile",
                "ALTER TABLE scan_run ADD COLUMN rule_profile VARCHAR(64) NOT NULL DEFAULT 'COMMUNITY_GENERAL' AFTER baseline_ref");
        ensureColumn("scan_project", "target_java_version",
                "ALTER TABLE scan_project ADD COLUMN target_java_version INT NULL");
        ensureColumn("scan_run", "source_report_run_id",
                "ALTER TABLE scan_run ADD COLUMN source_report_run_id BIGINT NULL");
        alterColumn("ALTER TABLE scan_finding MODIFY COLUMN cwe VARCHAR(512) NULL");
        alterColumn("ALTER TABLE scan_ai_review MODIFY COLUMN prompt_version VARCHAR(128) NOT NULL");
        failInterruptedRuns();
        failInterruptedAiReviewJobs();
    }

    private void ensureColumn(String tableName, String columnName, String ddl) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
                if (columns.next()) {
                    return;
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(ddl);
            }
        }
    }

    private void alterColumn(String ddl) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private void failInterruptedRuns() throws Exception {
        String sql = "UPDATE scan_run SET status = 'FAILED', " +
                "error_message = '服务重启或本地进程中断，扫描未正常完成', " +
                "finished_at = NOW(3) WHERE status IN ('PENDING', 'PREPARING', 'RUNNING')";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void failInterruptedAiReviewJobs() throws Exception {
        String sql = "UPDATE scan_ai_review_job SET status = 'FAILED', " +
                "error_message = '服务重启或本地进程中断，AI 审核未正常完成', " +
                "finished_at = NOW(3) WHERE status IN ('PENDING', 'RUNNING')";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
