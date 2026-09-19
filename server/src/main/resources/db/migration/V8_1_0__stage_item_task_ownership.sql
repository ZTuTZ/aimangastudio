-- Phase 8.1 Task + Stage Item 双层 fencing；兼容已手工迁移的数据库。
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pipeline_stage_item' AND COLUMN_NAME = 'owner_task_id');
SET @sql := IF(@exist = 0,
  'ALTER TABLE pipeline_stage_item ADD COLUMN owner_task_id BIGINT NULL COMMENT ''领取 Item 的 Task ID''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pipeline_stage_item' AND COLUMN_NAME = 'owner_task_claim_token');
SET @sql := IF(@exist = 0,
  'ALTER TABLE pipeline_stage_item ADD COLUMN owner_task_claim_token VARCHAR(64) NULL COMMENT ''领取 Item 时的 Task claim token''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pipeline_stage_item' AND INDEX_NAME = 'idx_item_task_owner');
SET @sql := IF(@exist = 0,
  'ALTER TABLE pipeline_stage_item ADD KEY idx_item_task_owner (owner_task_id, owner_task_claim_token)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
