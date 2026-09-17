-- AIMangaStudio v2 Phase 7.8
-- Comic Text Layer:动态对白层(底图与文本层解耦,归一化坐标)
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

CREATE TABLE IF NOT EXISTS `page_text_element` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    `project_id` BIGINT UNSIGNED NOT NULL,
    `chapter_id` BIGINT UNSIGNED NOT NULL,
    `page_id` BIGINT UNSIGNED NOT NULL,

    `element_uid` VARCHAR(64) NOT NULL,

    `element_type` VARCHAR(32) NOT NULL COMMENT 'DIALOGUE/NARRATION/THOUGHT/SFX',

    `dialogue_index` INT DEFAULT NULL COMMENT '对应page.dialogue数组索引,旁白可为空',

    `speaker` VARCHAR(128) DEFAULT NULL,
    `text_content` TEXT NOT NULL,

    `x` DECIMAL(8,6) NOT NULL,
    `y` DECIMAL(8,6) NOT NULL,
    `width` DECIMAL(8,6) NOT NULL,
    `height` DECIMAL(8,6) DEFAULT NULL,

    `tail_x` DECIMAL(8,6) DEFAULT NULL,
    `tail_y` DECIMAL(8,6) DEFAULT NULL,

    `bubble_style` VARCHAR(32) NOT NULL DEFAULT 'DEFAULT_DIALOGUE',
    `font_style` VARCHAR(32) NOT NULL DEFAULT 'DEFAULT_DIALOGUE',

    `font_size_ratio` DECIMAL(8,6) NOT NULL DEFAULT 0.028000,
    `text_align` VARCHAR(16) NOT NULL DEFAULT 'CENTER',

    `max_lines` INT DEFAULT NULL,
    `sort_order` INT NOT NULL DEFAULT 0,

    `source_type` VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'AUTO/MANUAL/AI',

    `version` INT NOT NULL DEFAULT 1,

    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_page_text_uid` (`element_uid`),
    KEY `idx_page_text_page` (`page_id`),
    KEY `idx_page_text_project` (`project_id`),

    CONSTRAINT `fk_page_text_page`
      FOREIGN KEY (`page_id`) REFERENCES `page` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='页动态文本层元素(归一化坐标)';

-- 文本层同步时的脚本版本(script_version > text_layout_version = 布局可能需要同步)
ALTER TABLE `page`
  ADD COLUMN `text_layout_version` INT NOT NULL DEFAULT 0 COMMENT '文本层同步时的脚本版本';
