-- AIMangaStudio v2 Phase 6.1
-- 页级素材绑定:page_asset_ref 表(Page → Asset 结构化绑定,出图预检依据)
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

CREATE TABLE IF NOT EXISTS `page_asset_ref` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT UNSIGNED NOT NULL,
  `page_id` BIGINT UNSIGNED NOT NULL,
  `asset_id` BIGINT UNSIGNED NOT NULL,
  `required_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '1必需(角色) 0可选(场景/道具/服装)',
  `source` VARCHAR(16) NOT NULL DEFAULT 'MATCH' COMMENT 'AI模型返回/MATCH程序匹配/MANUAL人工',
  `sort_order` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_page_asset` (`page_id`, `asset_id`),
  KEY `idx_par_project_page` (`project_id`, `page_id`),
  KEY `idx_par_asset` (`asset_id`),
  CONSTRAINT `fk_par_page` FOREIGN KEY (`page_id`) REFERENCES `page` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_par_asset` FOREIGN KEY (`asset_id`) REFERENCES `asset` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='页-资产素材绑定';
