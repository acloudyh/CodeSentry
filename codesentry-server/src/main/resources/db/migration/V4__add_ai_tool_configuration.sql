CREATE TABLE IF NOT EXISTS ai_tool_configuration (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    base_url VARCHAR(1000) NOT NULL,
    model VARCHAR(200) NOT NULL,
    api_key_ciphertext TEXT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    default_tool TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_tool_enabled_default (enabled, default_tool, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
