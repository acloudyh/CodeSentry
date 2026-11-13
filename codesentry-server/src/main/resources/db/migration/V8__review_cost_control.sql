CREATE TABLE IF NOT EXISTS ai_review_cost_policy (
 id INT PRIMARY KEY, content_json LONGTEXT NOT NULL, updated_at DATETIME(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS ai_review_plan (
 id VARCHAR(40) PRIMARY KEY, scan_run_id BIGINT NOT NULL, content_json LONGTEXT NOT NULL,
 job_id BIGINT NULL, created_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL,
 INDEX idx_review_plan_run (scan_run_id)
);
CREATE TABLE IF NOT EXISTS ai_review_budget (
 job_id BIGINT PRIMARY KEY, plan_id VARCHAR(40) NOT NULL, content_json LONGTEXT NOT NULL,
 requests_used INT NOT NULL DEFAULT 0, input_reserved BIGINT NOT NULL DEFAULT 0, output_reserved BIGINT NOT NULL DEFAULT 0,
 cost_reserved DECIMAL(24,8) NOT NULL DEFAULT 0, input_actual BIGINT NOT NULL DEFAULT 0,
 output_actual BIGINT NOT NULL DEFAULT 0, usage_missing INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS ai_review_daily_budget (
 spend_date DATE PRIMARY KEY, cost_reserved DECIMAL(24,8) NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS ai_review_result_cache (
 cache_key VARCHAR(64) PRIMARY KEY, source_run_id BIGINT NOT NULL, source_finding_id BIGINT NOT NULL,
 primary_json LONGTEXT NOT NULL, verify_json LONGTEXT NOT NULL, created_at DATETIME(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS ai_review_reuse_log (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, job_id BIGINT NOT NULL, finding_id BIGINT NOT NULL,
 source_run_id BIGINT NOT NULL, source_finding_id BIGINT NOT NULL, created_at DATETIME(3) NOT NULL,
 INDEX idx_review_reuse_job (job_id)
);
CREATE TABLE IF NOT EXISTS finding_rule_classification (
 rule_id VARCHAR(300) PRIMARY KEY, issue_type VARCHAR(16) NOT NULL, reason VARCHAR(1000) NOT NULL,
 updated_at DATETIME(3) NOT NULL
);
