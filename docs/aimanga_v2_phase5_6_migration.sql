-- AIMangaStudio v2 Phase 5.6
-- 任务可靠性层:claim 锁/心跳/重试/超时/最后错误
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

ALTER TABLE `task`
  ADD COLUMN `heartbeat_time` DATETIME NULL COMMENT 'Worker心跳' AFTER `end_time`,
  ADD COLUMN `claim_token` VARCHAR(64) NULL COMMENT '任务执行锁(领取时生成,终态校验)' AFTER `heartbeat_time`,
  ADD COLUMN `retry_count` INT NOT NULL DEFAULT 0 COMMENT '看门狗自动重试次数' AFTER `claim_token`,
  ADD COLUMN `max_retry_count` INT NOT NULL DEFAULT 3 COMMENT '最大自动重试次数' AFTER `retry_count`,
  ADD COLUMN `timeout_seconds` INT NOT NULL DEFAULT 600 COMMENT '心跳超时阈值(秒),超过判定僵尸' AFTER `max_retry_count`,
  ADD COLUMN `processed_count` INT NOT NULL DEFAULT 0 COMMENT '已处理步数(成功+失败)' AFTER `timeout_seconds`,
  ADD COLUMN `last_error` TEXT NULL COMMENT '最后一次错误记录' AFTER `processed_count`,
  ADD INDEX `idx_task_watch` (`status`, `heartbeat_time`);

-- 校验:
-- SHOW COLUMNS FROM `task` LIKE 'heartbeat_time';
