-- AIMangaStudio v2 成品直出开关
-- page_direct_output: 1=直接生成成品页(跳过布局阶段); 0=先布局后成品(默认)

USE `aimanga_v2`;

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `remark`)
SELECT 'page_direct_output', '0', 'pipeline', '成品直出开关(1=直接出成品图跳过布局;0=先布局后成品)'
WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'page_direct_output');
