CREATE TABLE IF NOT EXISTS scan_project (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_name VARCHAR(128) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    local_path VARCHAR(1024) NULL,
    remote_url VARCHAR(1024) NULL,
    managed_path VARCHAR(1024) NULL,
    default_branch VARCHAR(255) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_project_name (project_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS scan_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    scan_mode VARCHAR(24) NOT NULL,
    workspace_mode VARCHAR(24) NOT NULL,
    scan_ref VARCHAR(255) NULL,
    baseline_ref VARCHAR(255) NULL,
    rule_profile VARCHAR(64) NOT NULL DEFAULT 'COMMUNITY_GENERAL',
    scan_commit VARCHAR(64) NULL,
    baseline_head_commit VARCHAR(64) NULL,
    merge_base_commit VARCHAR(64) NULL,
    repository_dirty TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    total_findings INT NOT NULL DEFAULT 0,
    new_findings INT NOT NULL DEFAULT 0,
    fixed_findings INT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    created_at DATETIME(3) NOT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_scan_run_project_created (project_id, created_at),
    KEY idx_scan_run_merge_base (project_id, merge_base_commit),
    CONSTRAINT fk_scan_run_project FOREIGN KEY (project_id) REFERENCES scan_project (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS scan_tool_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scan_run_id BIGINT NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL DEFAULT 'current',
    tool_version VARCHAR(128) NULL,
    status VARCHAR(24) NOT NULL,
    exit_code INT NULL,
    command_line VARCHAR(2048) NULL,
    working_directory VARCHAR(1024) NULL,
    raw_report_path VARCHAR(1024) NULL,
    output_log LONGTEXT NULL,
    error_message TEXT NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_scan_tool_run_scan (scan_run_id),
    CONSTRAINT fk_scan_tool_run_scan FOREIGN KEY (scan_run_id) REFERENCES scan_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS scan_finding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    scanner VARCHAR(64) NOT NULL,
    rule_id VARCHAR(255) NOT NULL,
    category VARCHAR(64) NULL,
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(512) NOT NULL,
    description TEXT NULL,
    recommendation TEXT NULL,
    cwe VARCHAR(512) NULL,
    first_seen_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_finding_fingerprint (project_id, fingerprint),
    KEY idx_scan_finding_severity (project_id, severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS scan_finding_instance (
    id BIGINT NOT NULL AUTO_INCREMENT,
    finding_id BIGINT NOT NULL,
    scan_run_id BIGINT NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    start_line INT NULL,
    end_line INT NULL,
    code_snippet TEXT NULL,
    result_status VARCHAR(24) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_finding_instance (finding_id, scan_run_id),
    KEY idx_finding_instance_run_status (scan_run_id, result_status),
    CONSTRAINT fk_finding_instance_finding FOREIGN KEY (finding_id) REFERENCES scan_finding (id),
    CONSTRAINT fk_finding_instance_run FOREIGN KEY (scan_run_id) REFERENCES scan_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS scan_triage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    finding_id BIGINT NOT NULL,
    triage_status VARCHAR(24) NOT NULL,
    comment TEXT NULL,
    reviewer VARCHAR(128) NOT NULL,
    expires_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scan_triage_finding (project_id, finding_id),
    CONSTRAINT fk_scan_triage_project FOREIGN KEY (project_id) REFERENCES scan_project (id),
    CONSTRAINT fk_scan_triage_finding FOREIGN KEY (finding_id) REFERENCES scan_finding (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
