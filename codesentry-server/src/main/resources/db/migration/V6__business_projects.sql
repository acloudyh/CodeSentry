CREATE TABLE IF NOT EXISTS business_project (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(128) NOT NULL,
 description VARCHAR(2000) NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS business_project_asset (
 asset_id BIGINT NOT NULL PRIMARY KEY,
 business_project_id BIGINT NOT NULL,
 KEY idx_business_asset_project (business_project_id),
 FOREIGN KEY (asset_id) REFERENCES scan_project(id),
 FOREIGN KEY (business_project_id) REFERENCES business_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS project_scan_round (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 business_project_id BIGINT NOT NULL,
 project_name VARCHAR(128) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 full_scope BOOLEAN NOT NULL,
 asset_count INT NOT NULL,
 unselected_json LONGTEXT NOT NULL,
 network_mode VARCHAR(16) NOT NULL,
 rule_profile VARCHAR(64) NOT NULL,
 retry_of BIGINT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_project_round_request (business_project_id, request_key),
 KEY idx_project_round_created (business_project_id, id),
 FOREIGN KEY (business_project_id) REFERENCES business_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS project_scan_item (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 round_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 asset_name VARCHAR(128) NOT NULL,
 source_type VARCHAR(16) NOT NULL,
 target_java_version INT NULL,
 workspace_mode VARCHAR(24) NOT NULL,
 scan_ref VARCHAR(255) NULL,
 artifact_path VARCHAR(1024) NULL,
 run_id BIGINT NULL,
 error_message TEXT NULL,
 UNIQUE KEY uk_round_asset (round_id, asset_id),
 FOREIGN KEY (round_id) REFERENCES project_scan_round(id),
 FOREIGN KEY (asset_id) REFERENCES scan_project(id),
 FOREIGN KEY (run_id) REFERENCES scan_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS project_report_snapshot (
 round_id BIGINT NOT NULL PRIMARY KEY,
 content_json LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL,
 FOREIGN KEY (round_id) REFERENCES project_scan_round(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
