-- AIMangaStudio v2 Phase 5.7
-- Pipeline 断点恢复:阶段级进度追踪
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

CREATE TABLE IF NOT EXISTS `comic_pipeline_stage` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT UNSIGNED NOT NULL,
  `stage_type` VARCHAR(32) NOT NULL COMMENT 'SPLIT/ASSET/SCRIPT/SHEET/LAYOUT/IMAGE/EXPORT',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0排队 1进行中 2成功 3失败 4暂停 5停止',
  `progress` INT NOT NULL DEFAULT 0 COMMENT '0-100',
  `total_count` INT NOT NULL DEFAULT 0,
  `success_count` INT NOT NULL DEFAULT 0,
  `failed_count` INT NOT NULL DEFAULT 0,
  `result_ref` JSON COMMENT '阶段结果引用(如 chapterIds/assetIds)',
  `error` VARCHAR(512) DEFAULT '',
  `start_time` DATETIME DEFAULT NULL,
  `finish_time` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stage_project_type` (`project_id`, `stage_type`),
  KEY `idx_stage_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流水线阶段进度';
