-- Phase 8.1:Stage Item Attempt Fencing(幂等:列已存在则跳过,兼容已手工迁移的库)
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pipeline_stage_item' AND COLUMN_NAME = 'attempt_no');
SET @sql := IF(@exist = 0,
  'ALTER TABLE pipeline_stage_item ADD COLUMN attempt_no INT NOT NULL DEFAULT 0 COMMENT ''执行代次''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pipeline_stage_item' AND COLUMN_NAME = 'attempt_token');
SET @sql := IF(@exist = 0,
  'ALTER TABLE pipeline_stage_item ADD COLUMN attempt_token VARCHAR(64) NULL COMMENT ''当前执行 fencing token''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pipeline_stage_item' AND COLUMN_NAME = 'claimed_at');
SET @sql := IF(@exist = 0,
  'ALTER TABLE pipeline_stage_item ADD COLUMN claimed_at DATETIME NULL COMMENT ''当前 attempt 领取时间''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pipeline_stage_item' AND COLUMN_NAME = 'finish_time');
SET @sql := IF(@exist = 0,
  'ALTER TABLE pipeline_stage_item ADD COLUMN finish_time DATETIME NULL COMMENT ''最后完成时间''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pipeline_stage_item' AND INDEX_NAME = 'idx_item_attempt');
SET @sql := IF(@exist = 0,
  'ALTER TABLE pipeline_stage_item ADD KEY idx_item_attempt (id, attempt_token)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
