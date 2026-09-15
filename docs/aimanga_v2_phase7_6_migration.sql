-- AIMangaStudio v2 Phase 7.6
-- APP Importer 模拟:未来漫画 APP 的最小阅读库结构(与生产库完全隔离)
-- 验证 comic-content-1.0 包在不含任何生产 ID 的前提下仍能还原 漫画→话→页

USE `aimanga_v2`;

CREATE TABLE IF NOT EXISTS `comic` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `content_uid` CHAR(36) NOT NULL COMMENT '跨系统稳定ID(与包 manifest 一致)',
  `title` VARCHAR(255) NOT NULL,
  `tagline` VARCHAR(64) DEFAULT '',
  `description` TEXT,
  `cover_url` VARCHAR(512) DEFAULT NULL,
  `category` VARCHAR(64) DEFAULT '',
  `tags` JSON DEFAULT NULL,
  `series_status` TINYINT DEFAULT 2,
  `aspect_ratio` VARCHAR(20) DEFAULT '3:4',
  `color_mode` VARCHAR(20) DEFAULT 'partial',
  `complete` TINYINT DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comic_content_uid` (`content_uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='[APP模拟]漫画';

CREATE TABLE IF NOT EXISTS `comic_chapter` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `comic_id` BIGINT UNSIGNED NOT NULL,
  `chapter_no` INT NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cc_comic_no` (`comic_id`, `chapter_no`),
  CONSTRAINT `fk_cc_comic` FOREIGN KEY (`comic_id`) REFERENCES `comic` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='[APP模拟]话';

CREATE TABLE IF NOT EXISTS `comic_page` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chapter_id` BIGINT UNSIGNED NOT NULL,
  `page_no` INT NOT NULL,
  `image_url` VARCHAR(512) NOT NULL,
  `file_path` VARCHAR(255) DEFAULT NULL COMMENT '包内相对路径',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cp_chapter_no` (`chapter_id`, `page_no`),
  CONSTRAINT `fk_cp_chapter` FOREIGN KEY (`chapter_id`) REFERENCES `comic_chapter` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='[APP模拟]页';
