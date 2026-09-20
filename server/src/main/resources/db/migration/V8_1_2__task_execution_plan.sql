-- Phase 8 task admission and durable execution plan.
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'plan_version');
SET @sql := IF(@exist = 0, 'ALTER TABLE task ADD COLUMN plan_version INT NOT NULL DEFAULT 0 COMMENT ''执行计划版本''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comic_page' AND COLUMN_NAME = 'dialogue');
SET @sql := IF(@exist = 0, 'ALTER TABLE comic_page ADD COLUMN dialogue JSON NULL, ADD COLUMN narration TEXT NULL, ADD COLUMN text_layer JSON NULL, ADD COLUMN script_version INT NULL, ADD COLUMN image_script_version INT NULL, ADD COLUMN text_layout_version INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'plan_initialized_at');
SET @sql := IF(@exist = 0, 'ALTER TABLE task ADD COLUMN plan_initialized_at DATETIME NULL COMMENT ''计划初始化时间''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task' AND COLUMN_NAME = 'plan_snapshot');
SET @sql := IF(@exist = 0, 'ALTER TABLE task ADD COLUMN plan_snapshot JSON NULL COMMENT ''服务端执行计划摘要''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS task_plan_unit (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  task_id BIGINT UNSIGNED NOT NULL,
  plan_version INT NOT NULL,
  stage_type VARCHAR(32) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  business_id BIGINT UNSIGNED NOT NULL,
  source_script_version INT NULL,
  source_revision BIGINT NULL,
  input_snapshot JSON NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  attempt_token VARCHAR(64) NULL,
  result_ref JSON NULL,
  error_message VARCHAR(512) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
  finish_time DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_plan_business (task_id, plan_version, stage_type, business_type, business_id),
  KEY idx_task_plan_status (task_id, plan_version, status),
  CONSTRAINT fk_task_plan_task FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务固定执行计划单元';

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pipeline_stage_item' AND COLUMN_NAME = 'plan_unit_id');
SET @sql := IF(@exist = 0, 'ALTER TABLE pipeline_stage_item ADD COLUMN plan_unit_id BIGINT UNSIGNED NULL COMMENT ''当前执行对应计划单元'', ADD KEY idx_item_plan_unit (plan_unit_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'page' AND COLUMN_NAME = 'image_revision');
SET @sql := IF(@exist = 0, 'ALTER TABLE page ADD COLUMN image_revision BIGINT NOT NULL DEFAULT 0 COMMENT ''成品图内容版本''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
