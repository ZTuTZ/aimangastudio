-- AIMangaStudio v2 Phase 8.4
-- Task Lease:执行租约 + 执行实例 + 单次 Attempt 最大执行时间(多实例安全)
-- 执行前请先备份 aimanga_v2 数据库。

USE `aimanga_v2`;

ALTER TABLE `task`
  ADD COLUMN `lease_until` DATETIME NULL COMMENT '执行租约截止',
  ADD COLUMN `worker_instance_id` VARCHAR(64) NULL COMMENT '执行实例',
  ADD COLUMN `max_execution_seconds` INT NOT NULL DEFAULT 3600 COMMENT '单次Attempt最大执行时间',
  ADD KEY `idx_task_lease` (`status`, `lease_until`);
