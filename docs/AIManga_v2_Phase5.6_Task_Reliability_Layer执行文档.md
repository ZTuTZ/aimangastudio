# AIManga v2 Phase 5.6

# Task Reliability Layer 任务可靠性增强执行文档

## 目标

在进入 Phase 6 大规模 PAGE/LAYOUT/IMAGE 生成之前，增强任务系统可靠性。

解决：

-   Redis 入队失败导致任务丢失
-   Worker 崩溃导致 RUNNING 僵尸任务
-   AI接口超时导致任务永久卡死
-   多Worker重复执行
-   重试状态混乱
-   任务执行不可追踪

设计原则：

1.  MySQL 是任务最终事实来源。
2.  Redis 只负责调度。
3.  所有任务必须支持恢复。
4.  所有生成任务必须幂等。

------------------------------------------------------------------------

## 1. task表修改

新增字段：

``` sql
ALTER TABLE task
ADD COLUMN heartbeat_time DATETIME NULL,
ADD COLUMN claim_token VARCHAR(64) NULL,
ADD COLUMN retry_count INT DEFAULT 0,
ADD COLUMN max_retry_count INT DEFAULT 3,
ADD COLUMN timeout_seconds INT DEFAULT 600,
ADD COLUMN last_error TEXT NULL;
```

用途：

-   heartbeat_time：Worker心跳
-   claim_token：任务执行锁
-   retry_count：重试次数
-   timeout_seconds：超时阈值
-   last_error：错误记录

------------------------------------------------------------------------

## 2. Task Claim机制

任务领取必须原子化。

流程：

PENDING

↓

生成 claim_token

↓

更新：

``` sql
UPDATE task
SET
status='RUNNING',
claim_token=?,
heartbeat_time=NOW()
WHERE id=?
AND status='PENDING';
```

只有影响行数为1才允许执行。

------------------------------------------------------------------------

## 3. Worker Heartbeat

运行中的任务每30秒刷新：

``` sql
UPDATE task
SET heartbeat_time=NOW()
WHERE id=?
AND claim_token=?;
```

防止误恢复。

------------------------------------------------------------------------

## 4. Redis任务补偿

Redis只负责调度。

增加：

`PendingTaskRecoverScheduler`

每分钟扫描：

``` sql
SELECT *
FROM task
WHERE status='PENDING'
AND create_time < NOW()-60秒;
```

重新进入Redis队列。

解决：

MySQL存在任务，但Redis消息丢失。

------------------------------------------------------------------------

## 5. 僵尸任务检测

增加：

`TaskWatchDog`

扫描：

-   status=RUNNING
-   heartbeat_time超过timeout_seconds

处理：

RUNNING -\> TIMEOUT

然后：

-   retry_count \< max_retry_count：重新进入PENDING
-   超过次数：FAILED

------------------------------------------------------------------------

## 6. AI调用统一超时

所有AI接口必须经过统一 AiClient。

要求：

-   connect timeout
-   read timeout
-   total timeout

禁止Handler直接调用HTTP。

------------------------------------------------------------------------

## 7. 重试机制

增加：

-   retry_count
-   max_retry_count

任务统计区分：

-   processed_count
-   success_count
-   failed_count

不要使用success_count作为断点。

------------------------------------------------------------------------

## 8. 幂等机制

所有生成任务必须有业务唯一键。

例如：

PAGE任务：

comic_uid + chapter_no + page_no

执行前检查是否已经存在成功结果。

避免重复生成正式数据。

------------------------------------------------------------------------

## 9. generation_record

增加生成记录，用于追踪：

-   prompt
-   model
-   seed
-   result_url
-   status
-   时间

支持后续重绘、模型替换和问题排查。

------------------------------------------------------------------------

## 10. 优雅停机

Worker停止：

1.  停止领取新任务
2.  等待运行任务
3.  超时退出
4.  Recovery接管未完成任务

禁止直接kill导致任务状态永久RUNNING。

------------------------------------------------------------------------

## 11. 验收测试

必须通过：

1.  Redis删除任务后，可以自动恢复。
2.  Worker执行中kill，可以检测并重新执行。
3.  两个Worker抢同一任务，只允许一个成功。
4.  AI接口永久等待，可以timeout恢复。
5.  同一PAGE任务重复执行，不产生重复正式数据。

------------------------------------------------------------------------

## Phase 5.6完成后进入 Phase 6

流程：

Phase5.5 长文本、资产、脚本一致性

↓

Phase5.6 任务可靠性增强

↓

Phase6 漫画页生成

↓

Phase7 发布包和APP导入
