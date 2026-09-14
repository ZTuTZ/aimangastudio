-- AIMangaStudio v2 Phase 6.5
-- 脚本版本控制:页脚本修改后布局/成品图标记过期(不删旧图,保留 generate_records)
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

ALTER TABLE `page`
  ADD COLUMN `script_version` INT NOT NULL DEFAULT 1 COMMENT '脚本版本:文本修改+1',
  ADD COLUMN `layout_script_version` INT NOT NULL DEFAULT 0 COMMENT '布局图生成时的脚本版本',
  ADD COLUMN `image_script_version` INT NOT NULL DEFAULT 0 COMMENT '成品图生成时的脚本版本';
