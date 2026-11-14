CREATE TABLE IF NOT EXISTS project_report_package (
 id VARCHAR(40) PRIMARY KEY,
 business_project_id BIGINT NOT NULL,
 round_id BIGINT NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 report_format VARCHAR(8) NOT NULL,
 status VARCHAR(16) NOT NULL,
 file_name VARCHAR(512) NOT NULL,
 scope_json LONGTEXT NOT NULL,
 total_files INT NOT NULL,
 completed_files INT NOT NULL DEFAULT 0,
 file_bytes BIGINT NOT NULL DEFAULT 0,
 error_message VARCHAR(1000) NULL,
 created_at DATETIME(3) NOT NULL,
 finished_at DATETIME(3) NULL,
 UNIQUE KEY uk_report_package_request (business_project_id,request_key),
 KEY idx_report_package_round (business_project_id,round_id,report_format,created_at),
 KEY idx_report_package_status (status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
