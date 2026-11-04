CREATE TABLE IF NOT EXISTS remediation_report_snapshot (
    scan_run_id BIGINT NOT NULL PRIMARY KEY,
    findings_json LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_report_snapshot_run FOREIGN KEY (scan_run_id) REFERENCES scan_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
