-- 仅保存不可逆键摘要和来源摘要，不保存请求体、密码或令牌；向前兼容旧版本。
CREATE TABLE IF NOT EXISTS http_idempotency (
  key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  INDEX idx_http_idempotency_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
