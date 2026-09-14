-- AIMangaStudio v2 Phase 5.8
-- Pipeline Stage Item 化:SCRIPT 等阶段内部执行单元独立持久化
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

CREATE TABLE IF NOT EXISTS `pipeline_stage_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT UNSIGNED NOT NULL,
  `stage_type` VARCHAR(32) NOT NULL COMMENT 'SPLIT/ASSET/SCRIPT/SHEET/LAYOUT/IMAGE/EXPORT',
  `business_type` VARCHAR(32) NOT NULL COMMENT '业务类型:CHAPTER/PAGE',
  `business_id` BIGINT UNSIGNED NOT NULL COMMENT '业务主键(chapter.id/page.id/asset.id)',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0排队 1进行中 2成功 3失败',
  `retry_count` INT NOT NULL DEFAULT 0,
  `result_ref` JSON COMMENT '结果引用(如 assetId/pageUrl)',
  `error_message` VARCHAR(512) DEFAULT '',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item` (`project_id`, `stage_type`, `business_type`, `business_id`),
  KEY `idx_item_status` (`project_id`, `stage_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流水线阶段执行单元';
