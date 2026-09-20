-- Controlled, expiring download metadata for asynchronous exports.
CREATE TABLE IF NOT EXISTS export_artifact (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  storage_url VARCHAR(2048) NULL,
  file_name VARCHAR(255) NOT NULL,
  byte_size BIGINT UNSIGNED NULL,
  sha256 CHAR(64) NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0=BUILDING,1=ACTIVE,2=EXPIRED',
  expires_at DATETIME NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_export_artifact_task (task_id),
  KEY idx_export_artifact_cleanup (status, expires_at),
  CONSTRAINT fk_export_artifact_task FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异步导出产物';
