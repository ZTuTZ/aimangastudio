-- Durable export object lifecycle and generation-fenced publication.
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'export_artifact' AND COLUMN_NAME = 'generation');
SET @sql := IF(@exist = 0, 'ALTER TABLE export_artifact ADD COLUMN generation BIGINT NOT NULL DEFAULT 0 AFTER status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'export_artifact' AND COLUMN_NAME = 'publisher_token');
SET @sql := IF(@exist = 0, 'ALTER TABLE export_artifact ADD COLUMN publisher_token VARCHAR(64) NULL AFTER generation', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'export_artifact' AND COLUMN_NAME = 'current_object_id');
SET @sql := IF(@exist = 0, 'ALTER TABLE export_artifact ADD COLUMN current_object_id BIGINT UNSIGNED NULL AFTER publisher_token', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS export_stored_object (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id BIGINT UNSIGNED NULL,
  plan_unit_id BIGINT UNSIGNED NULL,
  artifact_id BIGINT UNSIGNED NULL,
  generation BIGINT NOT NULL DEFAULT 0,
  kind VARCHAR(16) NOT NULL COMMENT 'CHECKPOINT/FINAL',
  storage_key VARCHAR(512) NOT NULL,
  storage_url VARCHAR(2048) NULL,
  state VARCHAR(24) NOT NULL COMMENT 'UPLOADING/RETAINED/DELETE_PENDING/DELETING/DELETED',
  byte_size BIGINT UNSIGNED NULL,
  sha256 CHAR(64) NULL,
  owner_token VARCHAR(64) NULL,
  upload_deadline DATETIME NULL,
  delete_after DATETIME NULL,
  cleanup_token VARCHAR(64) NULL,
  cleanup_lease_until DATETIME NULL,
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(500) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_export_object_key (storage_key),
  KEY idx_export_object_cleanup (state, delete_after),
  KEY idx_export_object_task (task_id),
  KEY idx_export_object_artifact (artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可追踪导出存储对象';

CREATE TABLE IF NOT EXISTS export_object_read_lease (
  token VARCHAR(64) NOT NULL,
  object_id BIGINT UNSIGNED NOT NULL,
  lease_until DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (token),
  KEY idx_export_read_lease_object (object_id, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='导出下载读租约';

INSERT INTO system_config(config_key, config_value, config_group, remark) VALUES
('export_checkpoint_ttl_hours','168','export','导出检查点保留小时数'),
('export_temp_max_bytes','2147483648','export','单节点导出临时文件总预算'),
('export_download_max_seconds','3600','export','单次导出下载最长秒数'),
('export_upload_timeout_seconds','3600','export','导出对象上传最长秒数')
ON DUPLICATE KEY UPDATE config_key = VALUES(config_key);
