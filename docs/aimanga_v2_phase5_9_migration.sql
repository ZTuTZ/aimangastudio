-- AIMangaStudio v2 Phase 5.9
-- 高性能并发生图引擎:配置项种子(无需 DDL,pipeline_stage_item 表结构不变)
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

-- 生图引擎并发配置(Phase 5.9 §3/§4/§6)
-- image_generation_concurrency:同时进行的 AI 生图请求数上限(热更新,平滑扩缩容)
-- image_gen_max_retry:单图失败自动重试次数(重试期间该 Item 回到 PENDING,不影响其他 Item)
-- image_queue_size:生图 Worker 池队列容量(进程启动时读取,重启生效)
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `remark`) VALUES
  ('image_generation_concurrency', '5',  'pipeline', '生图引擎并发数(热更新,1-32)'),
  ('image_gen_max_retry',          '3',  'pipeline', '生图单元失败重试次数'),
  ('image_queue_size',             '50', 'pipeline', '生图线程池队列容量(重启生效)')
ON DUPLICATE KEY UPDATE `remark` = VALUES(`remark`);
