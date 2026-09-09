-- AIMangaStudio v2 Phase 3.5
-- 漫画内容兼容字段迁移
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

ALTER TABLE `project`
  ADD COLUMN `content_uid` CHAR(36) DEFAULT NULL COMMENT '跨系统稳定作品ID' AFTER `id`,
  ADD COLUMN `description` TEXT COMMENT '漫画正式简介' AFTER `tagline`,
  ADD COLUMN `cover_url` VARCHAR(512) DEFAULT NULL COMMENT '漫画封面OSS URL' AFTER `description`,
  ADD COLUMN `category` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '漫画主分类' AFTER `cover_url`,
  ADD COLUMN `tags` JSON DEFAULT NULL COMMENT '漫画标签数组' AFTER `category`,
  ADD COLUMN `series_status` TINYINT NOT NULL DEFAULT 2 COMMENT '1连载中 2已完结' AFTER `tags`;

-- 为存量作品生成稳定跨系统 ID。
UPDATE `project`
SET `content_uid` = UUID()
WHERE `content_uid` IS NULL OR `content_uid` = '';

-- 存量短简介作为正式简介的初始兜底，后续 Phase 5 可由 AI 补全。
UPDATE `project`
SET `description` = `tagline`
WHERE (`description` IS NULL OR `description` = '')
  AND `tagline` IS NOT NULL
  AND `tagline` <> '';

ALTER TABLE `project`
  MODIFY COLUMN `content_uid` CHAR(36) NOT NULL COMMENT '跨系统稳定作品ID',
  ADD UNIQUE KEY `uk_project_content_uid` (`content_uid`);

-- 校验建议：
-- SELECT COUNT(*) total, COUNT(DISTINCT content_uid) uid_count,
--        SUM(content_uid IS NULL OR content_uid='') empty_uid
-- FROM project;
-- 预期 total = uid_count，empty_uid = 0。
