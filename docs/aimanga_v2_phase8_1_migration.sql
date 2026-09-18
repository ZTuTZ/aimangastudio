-- AIMangaStudio v2 Phase 8.1
-- Stage Item Attempt Fencing:执行可重复,但只有最新合法 Attempt 有权提交正式业务结果
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

ALTER TABLE `pipeline_stage_item`
  ADD COLUMN `attempt_no` INT NOT NULL DEFAULT 0 COMMENT '执行代次',
  ADD COLUMN `attempt_token` VARCHAR(64) NULL COMMENT '当前执行 fencing token',
  ADD COLUMN `claimed_at` DATETIME NULL COMMENT '当前 attempt 领取时间',
  ADD COLUMN `finish_time` DATETIME NULL COMMENT '最后完成时间',
  ADD KEY `idx_item_attempt` (`id`, `attempt_token`);
