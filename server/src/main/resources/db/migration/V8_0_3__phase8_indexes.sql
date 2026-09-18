-- Phase 8.0 §13 索引复核补充(幂等)
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'page' AND INDEX_NAME = 'idx_page_gen_status');
SET @sql := IF(@exist = 0,
  'ALTER TABLE page ADD KEY idx_page_gen_status (project_id, generate_status)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
