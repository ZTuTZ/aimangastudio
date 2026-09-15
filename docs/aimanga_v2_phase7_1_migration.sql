-- AIMangaStudio v2 Phase 7.1
-- 配置中心收尾:单页参考图上限 + 素材 Gate 开关种子(已存在则不覆盖)

USE `aimanga_v2`;

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `remark`)
SELECT 'page_reference_max_images', '8', 'pipeline', '单页出图最大参考图数量(布局图外)'
WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'page_reference_max_images');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `remark`)
SELECT 'page_generation_asset_gate', '1', 'pipeline', '素材Gate(1=缺必需角色阻止出图;0=仅警告放行)'
WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'page_generation_asset_gate');
