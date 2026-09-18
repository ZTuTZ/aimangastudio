-- Phase 8.3/8.4:Task PAUSED 状态 + 执行租约(幂等)
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'page' AND COLUMN_NAME = 'text_layout_version');
SET @sql := IF(@exist = 0,
  'ALTER TABLE page ADD COLUMN text_layout_version INT NOT NULL DEFAULT 0 COMMENT ''文本层同步时的脚本版本''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'lease_until');
SET @sql := IF(@exist = 0,
  'ALTER TABLE task ADD COLUMN lease_until DATETIME NULL COMMENT ''执行租约截止''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'worker_instance_id');
SET @sql := IF(@exist = 0,
  'ALTER TABLE task ADD COLUMN worker_instance_id VARCHAR(64) NULL COMMENT ''执行实例''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'max_execution_seconds');
SET @sql := IF(@exist = 0,
  'ALTER TABLE task ADD COLUMN max_execution_seconds INT NOT NULL DEFAULT 3600 COMMENT ''单次Attempt最大执行时间''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND INDEX_NAME = 'idx_task_lease');
SET @sql := IF(@exist = 0,
  'ALTER TABLE task ADD KEY idx_task_lease (status, lease_until)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
