-- AIMangaStudio v2 数据库结构(MySQL 8,utf8mb4)
CREATE DATABASE IF NOT EXISTS `aimanga_v2` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `aimanga_v2`;

-- 用户(仅管理员创建账号;ADMIN 全部权限,USER 仅漫画生产)
CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) NOT NULL,
  `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt',
  `role` VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'ADMIN/USER',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- 作品(剧)
CREATE TABLE IF NOT EXISTS `project` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `content_uid` CHAR(36) NOT NULL COMMENT '跨系统稳定作品ID',
  `user_id` BIGINT UNSIGNED NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `source_text` LONGTEXT COMMENT '故事原文',
  `aspect_ratio` VARCHAR(20) NOT NULL DEFAULT '3:4' COMMENT '3:4/2:3/1:1/16:9',
  `color_mode` VARCHAR(20) NOT NULL DEFAULT 'partial' COMMENT 'partial局部上色/monochrome黑白/color全彩',
  `style_preset_id` BIGINT UNSIGNED DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0准备中1待出图2出图中3完成4部分失败',
  `tagline` VARCHAR(64) DEFAULT '' COMMENT '短简介/一句话卖点',
  `description` TEXT COMMENT '漫画正式简介',
  `cover_url` VARCHAR(512) DEFAULT NULL COMMENT '漫画封面OSS URL',
  `category` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '漫画主分类',
  `tags` JSON DEFAULT NULL COMMENT '漫画标签数组',
  `series_status` TINYINT NOT NULL DEFAULT 2 COMMENT '1连载中 2已完结',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_content_uid` (`content_uid`),
  KEY `idx_project_user` (`user_id`),
  KEY `idx_project_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作品';

-- 话(章节)
CREATE TABLE IF NOT EXISTS `chapter` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT UNSIGNED NOT NULL,
  `chapter_no` INT NOT NULL,
  `title` VARCHAR(255) NOT NULL DEFAULT '',
  `script_text` LONGTEXT COMMENT '本话故事原文',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理1脚本生成中2脚本就绪3出图中4完成5部分失败',
  `page_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chapter_project_no` (`project_id`, `chapter_no`),
  KEY `idx_chapter_project` (`project_id`),
  CONSTRAINT `fk_chapter_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='话';

-- 页
CREATE TABLE IF NOT EXISTS `page` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT UNSIGNED NOT NULL,
  `chapter_id` BIGINT UNSIGNED NOT NULL,
  `page_no` INT NOT NULL,
  `narration` TEXT COMMENT '旁白(矩形框)',
  `dialogue` JSON COMMENT '对白[{speaker,line}](气泡)',
  `visual` TEXT COMMENT '画面详述',
  `scene_description` TEXT COMMENT '合成展示脚本(旁白+对白+画面)',
  `layout_image_url` VARCHAR(512) COMMENT 'AI 分镜布局图',
  `generated_image_url` VARCHAR(512) COMMENT '成品页',
  `color_mode` VARCHAR(20) COMMENT '本页成图色彩模式',
  `generate_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待生成1生成中2成功3失败',
  `fail_reason` VARCHAR(512) DEFAULT '',
  `generate_records` JSON COMMENT '生成历史[{url,colorMode,kind,time}]',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_page_chapter_no` (`chapter_id`, `page_no`),
  KEY `idx_page_project` (`project_id`),
  CONSTRAINT `fk_page_chapter` FOREIGN KEY (`chapter_id`) REFERENCES `chapter` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='页';

-- 资产(角色/场景/道具/服装)
CREATE TABLE IF NOT EXISTS `asset` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT UNSIGNED NOT NULL,
  `asset_type` TINYINT NOT NULL COMMENT '1角色 2场景 3道具 4服装',
  `name` VARCHAR(128) NOT NULL,
  `aliases` JSON COMMENT '别名数组(同人合并)',
  `description` TEXT,
  `structured` JSON COMMENT '结构化设定(角色:{role,age,hair,accessories,top,bottom})',
  `reference_url` VARCHAR(512) COMMENT '参考图(OSS)',
  `sheet_image_url` VARCHAR(512) COMMENT '设定表图(角色=六姿势)',
  `gen_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0空闲 1生成中 9失败',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_project_type_name` (`project_id`, `asset_type`, `name`),
  KEY `idx_asset_project` (`project_id`),
  CONSTRAINT `fk_asset_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资产';

-- 任务(MySQL 为事实来源;Redis 只做队列/信号量/进度缓存/事件)
CREATE TABLE IF NOT EXISTS `task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `project_id` BIGINT UNSIGNED DEFAULT NULL,
  `chapter_id` BIGINT UNSIGNED DEFAULT NULL,
  `task_type` VARCHAR(32) NOT NULL COMMENT 'SPLIT/SCRIPT/ASSET/SHEET/BATCH/PAGE/LAYOUT/COLORIZE/CLEAN/REPAINT',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0排队中1进行中2成功3失败4部分失败5已停止6停止中',
  `priority` INT NOT NULL DEFAULT 0,
  `progress` INT NOT NULL DEFAULT 0 COMMENT '0-100',
  `total_count` INT NOT NULL DEFAULT 0,
  `success_count` INT NOT NULL DEFAULT 0,
  `fail_count` INT NOT NULL DEFAULT 0,
  `current_no` INT NOT NULL DEFAULT 0 COMMENT '当前处理页/BATCH 断点',
  `payload` JSON COMMENT '任务参数(colorMode/skipGenerated/pageId/maskUrl/repaintPrompt...)',
  `error` VARCHAR(512) DEFAULT '',
  `result` JSON COMMENT '任务结果(如 failedPages)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `start_time` DATETIME DEFAULT NULL,
  `end_time` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_user` (`user_id`),
  KEY `idx_task_status` (`status`),
  KEY `idx_task_project` (`project_id`),
  CONSTRAINT `fk_task_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务';

-- 系统配置(全部可配置化;密钥仅存后端,读取脱敏)
CREATE TABLE IF NOT EXISTS `system_config` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `config_key` VARCHAR(128) NOT NULL,
  `config_value` TEXT,
  `config_group` VARCHAR(64) NOT NULL DEFAULT 'default',
  `remark` VARCHAR(255) DEFAULT '',
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置';

-- 风格预设
CREATE TABLE IF NOT EXISTS `style_preset` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(128) NOT NULL,
  `positive_prompt` TEXT COMMENT '风格提示词',
  `negative_prompt` TEXT COMMENT '负面提示词',
  `color_mode` VARCHAR(20) COMMENT '建议色彩模式 partial/monochrome/color',
  `ref_images` JSON COMMENT '风格参考图 URL 数组',
  `sort` INT NOT NULL DEFAULT 0,
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `remark` VARCHAR(255) DEFAULT '',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_preset_status_sort` (`status`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风格预设';
