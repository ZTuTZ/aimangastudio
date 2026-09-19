-- Phase 8.3/Task 6:项目与任务暂停意图独立持久化。
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project' AND COLUMN_NAME = 'pause_requested');
SET @sql := IF(@exist = 0, 'ALTER TABLE project ADD COLUMN pause_requested TINYINT NOT NULL DEFAULT 0 COMMENT ''暂停意图''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project' AND COLUMN_NAME = 'control_version');
SET @sql := IF(@exist = 0, 'ALTER TABLE project ADD COLUMN control_version BIGINT NOT NULL DEFAULT 0 COMMENT ''控制版本''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'pause_requested');
SET @sql := IF(@exist = 0, 'ALTER TABLE task ADD COLUMN pause_requested TINYINT NOT NULL DEFAULT 0 COMMENT ''单任务暂停意图''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'control_version');
SET @sql := IF(@exist = 0, 'ALTER TABLE task ADD COLUMN control_version BIGINT NOT NULL DEFAULT 0 COMMENT ''控制版本''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
