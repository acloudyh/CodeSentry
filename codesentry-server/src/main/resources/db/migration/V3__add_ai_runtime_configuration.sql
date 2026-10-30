CREATE TABLE IF NOT EXISTS ai_runtime_configuration (
    id TINYINT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    base_url VARCHAR(1000) NULL,
    model VARCHAR(200) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
