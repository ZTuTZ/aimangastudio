-- AIMangaStudio v2 Phase 5.10
-- 素材工作台:角色设定表/素材参考图改为用户按需批量生成 + 三类素材画幅配置
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

-- 1. 作品表增加素材参考图画幅配置(场景/道具/服装;可随时在详情页修改)
ALTER TABLE `project`
  ADD COLUMN `scene_ratio` VARCHAR(20) NOT NULL DEFAULT '16:9' COMMENT '场景参考图画幅(默认16:9)' AFTER `aspect_ratio`,
  ADD COLUMN `prop_ratio` VARCHAR(20) NOT NULL DEFAULT '1:1' COMMENT '道具参考图画幅(默认1:1)' AFTER `scene_ratio`,
  ADD COLUMN `costume_ratio` VARCHAR(20) NOT NULL DEFAULT '3:4' COMMENT '服装参考图画幅(默认3:4)' AFTER `prop_ratio`;

-- 2. 素材自动生成开关默认关闭(0):SCRIPT 完成后停在「待出图」,素材由用户在资产库勾选生成。
--    本次产品行为变更,无论旧值是什么都置 0;如需恢复全自动,在配置中心改回 1 即可。
UPDATE `system_config` SET `config_value` = '0'
WHERE `config_key` = 'feature_auto_sheet';

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `remark`)
SELECT 'feature_auto_sheet', '0', 'pipeline', '脚本完成后自动生成全部角色设定表(0=关闭,素材改为用户按需生成)'
WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'feature_auto_sheet');
