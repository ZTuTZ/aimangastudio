-- AIMangaStudio v2 Phase 6.7
-- 生成记录独立表:回溯/对比模型/排查批次/恢复历史版本的依据(page.generate_records JSON 同时保留)

USE `aimanga_v2`;

CREATE TABLE IF NOT EXISTS `generation_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT UNSIGNED NOT NULL,
  `chapter_id` BIGINT UNSIGNED DEFAULT NULL,
  `page_id` BIGINT UNSIGNED DEFAULT NULL,
  `task_id` BIGINT UNSIGNED DEFAULT NULL,
  `kind` VARCHAR(32) NOT NULL COMMENT 'LAYOUT/PAGE/COLORIZE/CLEAN/REPAINT',
  `model` VARCHAR(128) DEFAULT '',
  `prompt` TEXT,
  `reference_urls` JSON COMMENT '参考图 URL 数组',
  `input_url` VARCHAR(512) DEFAULT NULL COMMENT '输入图(后处理为原图)',
  `result_url` VARCHAR(512) DEFAULT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED',
  `error` VARCHAR(512) DEFAULT '',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_gr_page` (`page_id`),
  KEY `idx_gr_project_kind` (`project_id`, `kind`),
  CONSTRAINT `fk_gr_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 生成记录';
