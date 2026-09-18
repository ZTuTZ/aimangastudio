# AIMangaStudio v2 Phase 8.0
# 系统稳定性与生产化优化执行文档

**适用仓库：** `ZTuTZ/aimangastudio`  
**基线分支：** `main`  
**基线 Commit：** `92f08402b5c393b0e9100b3d121060d41bf6b23a`

> 本文档用于当前漫画生产系统完成主体功能开发后的稳定性收口。  
> 本 Phase 不重做架构，而是在保留现有 `MySQL Task + Redis 调度 + PipelineStage + PipelineStageItem + ConcurrentStageRunner` 的前提下，解决高并发、断点恢复、暂停继续、局部生成、多实例、热更新、数据库迁移、流式导出和生产监控中的边界问题。

---

# 0. 当前架构基线

当前系统已经具备：

- MySQL `task` 作为任务事实源；
- Redis `RBlockingQueue` 作为任务调度队列；
- Task 级 `claim_token + heartbeat + watchdog`；
- `comic_pipeline_stage`；
- `pipeline_stage_item`；
- `ConcurrentStageRunner`；
- `ImageWorkerPool / ScriptWorkerPool`；
- LAYOUT / IMAGE 页级并发；
- SHEET / ASSET_REF 用户手动素材生成；
- BATCH `PROJECT / CHAPTER / CHAPTERS`；
- Page script version；
- GenerationRecord；
- Comic Text Layer；
- Comic Package 导出；
- Importer 验证。

本 Phase 只做“可靠性和生产化收口”。

---

# 0.1 必须保持的原则

```text
MySQL = 业务与任务事实源
Redis = 调度 / 锁 / 并发控制 / 事件加速

Task = 一次用户或系统任务
Stage = 项目级业务阶段
Stage Item = 可重试、可并发的最小业务单元
```

---

# 0.2 本 Phase 禁止顺手大改

本轮禁止：

- 不切 Redis Streams；
- 不上 Kafka / RabbitMQ；
- 不拆微服务；
- 不上 Kubernetes；
- 不更换 Spring / MyBatis；
- 不重做漫画 Prompt；
- 不恢复自动素材图流程；
- 不把每个 Page 再变成独立 Task；
- 不把 Text Layer 烧进图片。

---

# 1. 当前确认需要优先修复的问题

## P0-1：Stage Item 没有 Attempt Fencing

当前 Stage Item 只有：

```text
status
retry_count
result_ref
error_message
```

领取逻辑只有：

```text
PENDING → RUNNING
```

没有：

```text
attempt_no
attempt_token
```

而 `ConcurrentStageRunner` 存在：

```text
DRAIN_TIMEOUT
```

Runner 等待超时后可以释放 Stage Lock，但后台 AI 请求可能仍然返回。

可能出现：

```text
Attempt A
AI 很慢
↓
Runner A 超时退出
↓
新 Runner 回收 RUNNING Item
↓
Attempt B 成功
↓
Attempt A 晚返回
↓
A 把 B 的正式结果覆盖
```

这是 Phase 8.0 第一优先级。

---

## P0-2：BATCH Scope 与项目级 Stage Item 冲突

当前 BATCH 支持：

```text
PROJECT
CHAPTER
CHAPTERS
```

但 LAYOUT / IMAGE Stage 是项目级唯一 Stage。

当前 Scoped BATCH 同步 Item 时，如果使用“当前目标 pages”清理 orphan，可能把其他话的 Stage Item 删除。

同时 Runner 默认领取：

```text
project + stage 下所有 PENDING Item
```

没有“这次 Task 只允许执行哪些 Item”的 Scope。

风险：

- 单话生成顺便生成其他话；
- 单页重绘顺便跑其他待生成页；
- CHAPTERS 批量任务污染项目级 Stage Item。

---

## P0-3：局部生成可能错误把整个 Project 标 DONE

例如：

```text
20话作品
只生成第3话
第3话全部成功
```

不能因此：

```text
Project = DONE
```

Project 完成状态必须由整部作品重新计算。

---

## P0-4：Pause/Resume 不是同一个 Task

当前暂停后 Stage 进入 PAUSED，但 Handler 返回后，Task 可能按正常结束处理。

Resume 再根据 Stage 类型重新创建：

```text
新的 Task
payload={}
```

如果原 BATCH：

```json
{
  "scope": "CHAPTERS",
  "chapterIds": [3,4,5],
  "colorMode": "color",
  "forceImage": true
}
```

Resume 后这些上下文可能丢失。

正确设计：

> 暂停 Task A，继续还是 Task A。

---

## P0-5：TaskRecovery 目前是单实例思路

当前应用启动时会把：

```text
PENDING
RUNNING
STOPPING
```

全部改成：

```text
PENDING
```

并重新入队。

单实例完整重启时可工作，但多实例或滚动发布时：

```text
Instance B 启动
```

可能重置：

```text
Instance A 正在正常执行的 Task
```

同时：

```text
STOPPING → PENDING
```

会丢失用户停止意图。

---

## P1-1：Redis Semaphore 热更新存在容量漂移

当前基于：

```text
availablePermits
```

调整 target。

但：

```text
availablePermits != 配置总并发
```

如果当前已有部分许可被占用，refresh 可能错误增加新的许可，导致实际并发超过配置值。

---

## P1-2：Task Worker 使用共享 Redis Poison Pill

当前缩容通过：

```text
Redis Queue 中投递 -1
```

让 Worker 退出。

但这是共享业务队列：

```text
新 Worker
旧 Worker
```

都可能消费。

线程控制消息不应该放在业务任务队列。

---

## P1-3：Stage Worker Pool 缩容顺序可能异常

当前类似：

```java
setMaximumPoolSize(target);
setCorePoolSize(target);
```

如果 target 小于当前 core：

```text
maximum < current core
```

可能抛异常。

---

## P1-4：数据库 Migration 仍然手工维护

当前有：

```text
schema.sql
phase*.sql
```

随着项目功能已经完成，需要正式进入版本化 Migration。

---

## P1-5：ZIP 导出和图片下载仍然高内存

当前思路偏：

```text
完整图片 byte[]
完整 ZIP ByteArrayOutputStream
```

大量漫画页时会显著增加 JVM 内存。

---

# 2. Phase 8.0 总执行顺序

本地 Agent 严格按顺序：

```text
8.1 Stage Item Attempt Fencing
↓
8.2 Scoped Stage Run + 项目状态修复
↓
8.3 Pause/Resume 保留原 Task
↓
8.4 Task Lease + Recovery
↓
8.5 Worker Pool 热更新收口
↓
8.6 Redis Concurrency Limiter
↓
8.7 Flyway 数据库版本管理
↓
8.8 Streaming IO / Export
↓
8.9 CI + Failure Injection
↓
8.10 生产监控
```

每个阶段独立提交并独立验收。

---

# 3. Phase 8.1 —— Stage Item Attempt Fencing

## 3.1 目标

最终达到：

```text
执行可以重复
AI请求可以重复
Redis消息可以重复

但只有当前最新 Attempt
有权提交正式业务结果
```

也就是：

```text
at-least-once execution
+
fenced commit
=
effectively-once visible result
```

---

## 3.2 pipeline_stage_item 新字段

新增：

```sql
ALTER TABLE pipeline_stage_item
  ADD COLUMN attempt_no INT NOT NULL DEFAULT 0
    COMMENT '执行代次',
  ADD COLUMN attempt_token VARCHAR(64) NULL
    COMMENT '当前执行 fencing token',
  ADD COLUMN claimed_at DATETIME NULL
    COMMENT '当前 attempt 领取时间',
  ADD COLUMN finish_time DATETIME NULL
    COMMENT '最后完成时间',
  ADD KEY idx_item_attempt (id, attempt_token);
```

如果已存在则跳过。

---

## 3.3 Claim 改造

旧：

```java
claim(itemId)
```

改：

```java
claim(itemId, attemptToken)
```

SQL：

```sql
UPDATE pipeline_stage_item
SET
  status = 1,
  attempt_no = attempt_no + 1,
  attempt_token = #{attemptToken},
  claimed_at = NOW(),
  update_time = NOW()
WHERE
  id = #{id}
  AND status = 0;
```

领取成功后重新查询 Item。

---

## 3.4 新执行上下文

新增：

```java
public record StageItemExecution(
    PipelineStageItem item,
    String attemptToken
) {}
```

所有 processor 使用：

```text
StageItemExecution
```

而不是裸 `PipelineStageItem`。

---

## 3.5 Item 所有状态写必须校验 token

改造：

```text
releaseItem
markItemRetry
markItemFailed
markItemSuccess
```

都必须：

```sql
WHERE
  id = ?
  AND status = RUNNING
  AND attempt_token = ?
```

如果旧 Attempt token 已失效：

```text
update rows = 0
```

必须记录：

```text
stale_commit_rejected
```

---

## 3.6 resetRunningItems

恢复：

```text
RUNNING → PENDING
```

同时：

```text
attempt_token = NULL
claimed_at = NULL
```

这样旧执行立即失去提交权。

---

## 3.7 业务表也必须 Fencing

仅仅保护 Stage Item 状态不够。

错误示例：

```text
旧 Attempt
↓
更新 page.generated_image_url
↓
markItemSuccess 被 token 拒绝
```

此时正式图片已经被旧结果覆盖。

所以新增：

```java
StageItemCommitService
```

---

## 3.8 StageItemCommitService

推荐按业务拆方法：

```text
commitScript(...)
commitSheet(...)
commitAssetReference(...)
commitPageLayout(...)
commitPageImage(...)
```

事务流程：

```text
BEGIN

SELECT pipeline_stage_item
FOR UPDATE
WHERE id = ?

校验：
status == RUNNING
attempt_token == 当前 token

不满足：
return false

满足：
更新业务表
更新 generation_record
Stage Item → SUCCESS

COMMIT
```

---

## 3.9 AI / OSS 不进入长事务

正确顺序：

```text
AI
↓
OSS
↓
短事务：
  lock Stage Item
  校验 Attempt
  写业务结果
  写 GenerationRecord
  Item SUCCESS
↓
commit
```

如果 Attempt 已失效：

```text
不写业务 DB
```

OSS orphan 暂时允许。

---

## 3.10 必须覆盖的业务 Stage

至少：

```text
SCRIPT
SHEET
REFERENCE / ASSET_REF
LAYOUT
IMAGE
```

---

## 3.11 验收测试

### A：旧 Attempt 晚返回

A 卡住 → B 接管成功 → A 返回。

要求：

```text
最终业务结果必须是 B
A 被拒绝
```

### B：Retry

A 失败并 retry，B 领取后，A 任何晚回调不能提交。

### C：双 Runner

同时 claim 同 Item，只能一个成功。

---

# 4. Phase 8.2 —— Scoped Stage Run

## 4.1 目标

明确区分：

```text
项目的 Stage Item 注册表
```

和：

```text
某一个 Task 本次允许执行的 Item
```

---

## 4.2 新增 StageRunScope

```java
public record StageRunScope(
    String businessType,
    Set<Long> businessIds
) {}
```

提供：

```text
forProjectPages
forChapter
forChapters
forPage
forAssets
```

---

## 4.3 ConcurrentStageRunner 接口

改成：

```java
run(
  projectId,
  stageType,
  scope,
  runtime,
  processor,
  engine
)
```

`claimNext()` 只能领取：

```text
scope.businessIds
```

内的 Item。

---

## 4.4 Mapper

新增：

```text
selectPendingIdsInScope(...)
```

条件：

```text
project_id
stage_type
business_type
business_id IN (...)
status=PENDING
```

当前项目规模可直接用 IN。

---

## 4.5 Orphan 清理规则

禁止：

```text
当前 Task pages
```

直接作为：

```text
合法 Stage Item 全量列表
```

Orphan 只能表示：

> 对应业务对象真的已经从项目删除。

建议拆：

```text
syncTargetItems()
cleanupProjectOrphans()
```

Scoped BATCH 只调用：

```text
syncTargetItems
```

---

## 4.6 BATCH

### Layout

只对：

```text
当前 Scope Page
```

create/reset Item。

### Image

同样只处理当前 Scope。

其他话：

```text
Item 保持不变
```

---

## 4.7 单页任务

单页：

```text
scope = {pageId}
```

即使项目还有 100 个其他 PENDING IMAGE Item，也不能领取。

---

## 4.8 Stage 终态

项目级 Stage 不能因为某个 scoped Task 成功就直接 SUCCESS。

新增：

```java
refreshStageTerminalState(projectId, stageType)
```

规则：

```text
存在 PENDING/RUNNING
→ 未完成

全部终态 + failed=0
→ SUCCESS

全部终态 + failed>0
→ FAILED/PARTIAL
```

---

## 4.9 ProjectCompletionService

新增：

```java
recalculateChapter(chapterId)
recalculateProject(projectId)
```

### Chapter COMPLETE

该话全部正式 Page：

```text
generate_status = SUCCESS
generated_image_url != empty
```

### Project DONE

所有 Chapter/Page 均完成。

---

## 4.10 验收

### Case 1

20话，只生成第3话：

```text
只执行第3话
其他话 Item 不删除
Project 不 DONE
```

### Case 2

CHAPTERS：

```text
3,5,8
```

只执行目标话。

### Case 3

单页重绘：

```text
只允许该 page 发生 AI 调用
```

---

# 5. Phase 8.3 —— Pause / Resume 保留原 Task

## 5.1 TaskStatus 增加 PAUSED

新增：

```text
7 = PAUSED
```

状态方法拆分：

```text
terminal()
runnable()
executing()
resumable()
```

PAUSED：

```text
不是 terminal
不是 running
是 resumable
```

---

## 5.2 新增 TaskPauseSignal

类似：

```text
TaskStopSignal
```

新增：

```java
TaskPauseSignal
```

Stage Runner 检测到 Stage PAUSED：

1. 停止领取新 Item；
2. 等待已领取 Item 收尾；
3. 抛 TaskPauseSignal。

---

## 5.3 TaskRunner

catch：

```java
TaskPauseSignal
```

Task：

```text
RUNNING → PAUSED
```

保留：

```text
payload
progress
total_count
success_count
fail_count
current_no
```

清：

```text
claim_token
heartbeat
lease
```

不要标 SUCCESS。

---

## 5.4 Resume 原 Task

新增：

```java
TaskService.resume(taskId)
```

SQL：

```sql
UPDATE task
SET
  status = 0,
  error = '',
  claim_token = NULL,
  heartbeat_time = NULL
WHERE
  id = ?
  AND status = 7;
```

然后：

```text
enqueueIfAbsent(taskId)
```

原 payload 不修改。

---

## 5.5 Project Resume

`POST /projects/{id}/resume`

逻辑：

1. PAUSED Stage → PENDING；
2. 查询 project 下 PAUSED Task；
3. 恢复这些原 Task；
4. 不再根据 Stage 创建空 payload 新 Task。

---

## 5.6 重启行为

PAUSED Task：

```text
服务重启后仍然 PAUSED
```

用户不点击继续：

```text
永不自动恢复
```

---

## 5.7 验收

- CHAPTERS 3/4/5 暂停恢复仍然 3/4/5；
- forceImage 等参数不丢；
- Task ID 不变；
- Pause 后 restart 不自动执行。

---

# 6. Phase 8.4 —— Task Lease / Recovery

## 6.1 新字段

```sql
ALTER TABLE task
  ADD COLUMN lease_until DATETIME NULL
    COMMENT '执行租约截止',
  ADD COLUMN worker_instance_id VARCHAR(64) NULL
    COMMENT '执行实例',
  ADD COLUMN max_execution_seconds INT NOT NULL DEFAULT 3600
    COMMENT '单次Attempt最大执行时间';
```

---

## 6.2 Instance ID

每个 JVM 启动生成：

```text
UUID
```

Task claim 时记录。

---

## 6.3 Task Claim

写入：

```text
status=RUNNING
claim_token=UUID
worker_instance_id=instanceUUID
heartbeat_time=NOW()
lease_until=NOW()+90s
```

推荐：

```text
task_lease_seconds=90
heartbeat_interval=30
```

---

## 6.4 Heartbeat

必须 CAS：

```text
id
claim_token
status=RUNNING
```

更新：

```text
heartbeat_time
lease_until
```

---

## 6.5 WatchDog 分两种超时

### Lease Timeout

代表：

```text
Worker失联
```

条件：

```text
lease_until < NOW()
```

### Execution Timeout

代表：

```text
Worker仍有心跳
但这次Attempt运行过久
```

条件：

```text
attempt start + max_execution_seconds < NOW()
```

两者都可：

```text
revoke claim
retry/fail
```

旧执行晚回来由 8.1 fencing 拦截。

---

## 6.6 STOPPING

STOPPING 代表用户停止。

如果 Worker 已死：

```text
STOPPING → STOPPED
```

不能：

```text
→ PENDING
```

---

## 6.7 TaskRecovery

启动时：

```text
PENDING → enqueueIfAbsent

RUNNING → 不修改
           等 lease/watchdog

STOPPING → 不转 PENDING

PAUSED → 不动
```

---

## 6.8 Queue Marker

新增：

```text
aimanga:v2:task:queued:{taskId}
```

enqueue 使用：

```text
SET NX TTL
```

建议：

```text
TTL=120s
```

Worker 取出后删除 marker。

如果 Worker 取出后 claim 前 crash：

```text
marker 到期
Pending Recover 再入队
```

---

## 6.9 暂不引入 Outbox / Redis Streams

本轮继续：

```text
MySQL PENDING 补偿
+
Redis Queue
+
CAS Claim
```

足够当前规模。

---

# 7. Phase 8.5 —— Worker Pool 热更新

## 7.1 删除 Poison Pill

删除业务 Redis Queue 中：

```text
POISON_PILL
poison()
purgePoison()
removePoison()
```

---

## 7.2 TaskQueue 改 poll

新增：

```java
Long poll(long timeout, TimeUnit unit)
```

Worker：

```text
poll(2s)
```

方便本地停止。

---

## 7.3 WorkerHandle

TaskWorkerPool 内部维护：

```java
class WorkerHandle {
  AtomicBoolean stopRequested;
  Future<?> future;
}
```

### 扩容

新增 Worker。

### 缩容

多余 Worker：

```text
stopRequested=true
```

如果正在执行 Task：

```text
当前 Task 完成后再退出
```

不要 interrupt 业务请求。

---

## 7.4 Shutdown

```text
停止领取新任务
↓
当前任务收尾
↓
grace timeout
↓
未完成由 lease recovery 接管
```

---

## 7.5 Stage Pool resize 顺序

### 扩容

```java
setMaximumPoolSize(target);
setCorePoolSize(target);
```

### 缩容

```java
setCorePoolSize(target);
setMaximumPoolSize(target);
```

---

## 7.6 queue-size

`LinkedBlockingQueue` 创建后容量不可直接热改。

所以第一版：

```text
image_queue_size
script_queue_size
```

标记：

```text
重启生效
```

---

## 7.7 验收

连续：

```text
task 2 → 10 → 3 → 8
image 5 → 20 → 2 → 8
script 3 → 10 → 2
```

无异常，无任务丢失。

---

# 8. Phase 8.6 —— Redis Concurrency Limiter

## 8.1 目标

替换：

```text
RSemaphore availablePermits 调 target
```

这种容量语义。

---

## 8.2 新 RedisConcurrencyLimiter

建议 key：

```text
aimanga:v2:limit:ai:text
aimanga:v2:limit:ai:image
aimanga:v2:limit:ai:merge
aimanga:v2:limit:user:{uid}
```

---

## 8.3 Permit Token

每次 acquire：

```text
生成 permitToken
```

ZSET：

```text
member = token
score = expireTimestamp
```

Lua Acquire：

1. 清理过期 token；
2. ZCARD = used；
3. 读取 max；
4. used < max 才 ZADD；
5. 返回 token。

Release：

```text
ZREM token
```

---

## 8.4 热更新

只更新：

```text
max
```

例如：

```text
old max=10
used=8
new max=5
```

已有 8 个继续执行。

禁止新的 acquire，直到 used < 5。

---

## 8.5 Permit TTL

建议：

```text
AI timeout + 120秒
```

如果需要更严格可增加 renew。

---

## 8.6 Startup

删除：

```text
semaphores.resetAll()
```

多实例启动不能删除其他实例正在使用的限制状态。

---

# 9. Phase 8.7 —— Flyway 数据库版本管理

## 9.1 目标

数据库变更不再依赖：

```text
人工判断执行过哪些SQL
```

---

## 9.2 引入 Flyway

Spring Boot 3.3.5 使用兼容依赖：

```text
flyway-core
Flyway MySQL support
```

优先采用 Spring Boot dependency management。

---

## 9.3 目录

```text
server/src/main/resources/db/migration/
```

---

## 9.4 Baseline

把当前完整正式 Schema 整理成：

```text
V1__baseline_schema.sql
```

必须包含当前所有正式表和字段。

---

## 9.5 现有 DB

切换前：

```text
备份数据库
```

然后将现有 DB baseline 为：

```text
version 1
```

后续 Migration：

```text
V8_0_1__stage_item_fencing.sql
V8_0_2__task_pause_and_lease.sql
V8_0_3__phase8_indexes.sql
```

---

## 9.6 schema.sql

可保留参考，但 README 明确：

> 正式升级以 Flyway 为唯一来源。

CI 必须验证：

```text
空 MySQL
→ 应用启动
→ 自动得到完整 Schema
```

---

# 10. Phase 8.8 —— Streaming IO / Export

## 10.1 Publication ZIP

旧：

```text
byte[] exportZip()
```

改：

```text
writeZip(projectId, OutputStream out)
```

Controller：

```text
StreamingResponseBody
```

---

## 10.2 图片下载

使用：

```text
HttpResponse<InputStream>
```

而不是：

```text
BodyHandlers.ofByteArray()
```

直接：

```text
InputStream → OSS / ZipOutputStream
```

---

## 10.3 大小限制

新增配置：

```text
remote_image_max_bytes=31457280
```

默认：

```text
30MB
```

流式读取过程中计数，超过立即中止。

---

## 10.4 MIME

仅允许：

```text
image/png
image/jpeg
image/webp
image/gif
image/avif
```

必要时结合 magic bytes。

---

## 10.5 SSRF

抽象：

```java
RemoteImageFetcher
```

统一处理：

- 仅 http/https；
- 禁 localhost；
- 禁 loopback；
- 禁 link-local；
- 禁 RFC1918 私网；
- redirect 后重新校验；
- 限 redirect 数；
- provider CDN allow-list 配置化；
- 自有 OSS host 放行。

---

## 10.6 Batch Export

大量漫画导出：

```text
EXPORT Task
↓
生成临时文件 / OSS
↓
Task Result 返回下载URL
```

单部小漫画仍支持同步流式下载。

---

# 11. Phase 8.9 —— 自动化测试与 CI

## 11.1 Testcontainers

建议增加：

```text
MySQL
Redis
```

真实验证：

```text
CAS
row lock
lease
limiter
```

---

## 11.2 Failure Injection 必测

### F1
Task DB 创建成功，Redis enqueue 失败 → 最终补偿入队。

### F2
Redis pop 后、claim 前 crash → 最终再入队。

### F3
Task claim 后 crash → lease 过期接管。

### F4
Stage Attempt A 晚返回 → 不覆盖 B。

### F5
AI 成功 + OSS 成功，DB commit 前 crash → DB 不假成功。

### F6
业务 DB commit 后，Task SUCCESS 前 crash → 恢复时幂等。

### F7
CHAPTERS BATCH 暂停 → Resume 原 Task + 原 payload。

### F8
PAUSED 后 restart → 仍 PAUSED。

### F9
STOPPING crash → 最终 STOPPED。

### F10
双实例启动 → 不互相重置 RUNNING。

### F11
单页重绘 + 其他 PENDING Item → 只跑目标页。

### F12
Chapter BATCH 成功 → Project 不错误 DONE。

### F13
热缩容 → 无异常、无任务丢失。

### F14
Redis 短暂不可用 → MySQL PENDING 最终恢复。

---

## 11.3 前端测试

增加：

```text
ESLint
Vitest
React Testing Library
```

优先测：

1. Text Layer normalized coordinate；
2. viewport resize；
3. BATCH Scope Payload；
4. Pause / Resume UI；
5. SSE reconnect；
6. GenerationWorkbench Scope；
7. stale image version。

---

## 11.4 GitHub Actions

新增：

```text
.github/workflows/ci.yml
```

Backend：

```bash
mvn test
```

Frontend：

```bash
npm ci
npm run lint
npm run test:run
npm run build
```

---

# 12. Phase 8.10 —— 生产监控

不强制重新引入 Actuator。

继续扩展：

```text
HealthController
AdminMonitorController
```

---

## 12.1 Task 指标

```text
queue_depth
oldest_pending_age_seconds
running_tasks
paused_tasks
stopping_tasks
stale_running_tasks
task_retry_count
```

---

## 12.2 Stage 指标

```text
stage_item_pending
stage_item_running
stage_item_success
stage_item_failed
stage_item_retry_total
stage_item_stale_commit_rejected
```

重点观察：

```text
stale_commit_rejected
```

说明 fencing 正在真实保护系统。

---

## 12.3 AI 指标

按：

```text
text
image
merge
```

统计：

```text
request_total
success_total
failure_total
timeout_total
duration_p50
duration_p95
duration_p99
concurrency_used/max
```

---

## 12.4 日志上下文

统一：

```text
taskId
projectId
stageType
stageItemId
businessId
attemptNo
attemptTokenPrefix
```

示例：

```text
[image] task=1001 project=88 stage=IMAGE item=9001 page=300 attempt=2 token=ab12cd
```

---

# 13. 索引复核

Task：

```text
(status, create_time)
(status, lease_until)
(project_id, task_type, status)
(user_id, status)
```

Stage Item：

```text
(project_id, stage_type, status, business_id)
(id, status, attempt_token)
```

Page：

```text
(project_id, chapter_id, page_no)
(project_id, generate_status)
```

以当前实际 Schema 为准，不重复创建。

---

# 14. API 兼容要求

保留：

```text
POST /api/projects/{id}/pause
POST /api/projects/{id}/resume
POST /api/projects/{id}/generate-batch
POST /api/pages/{id}/generate
POST /api/pages/{id}/generate-layout
```

内部 Resume 行为改成：

```text
恢复原 Task
```

不要求前端更换 URL。

---

# 15. Git 提交顺序

推荐：

```text
phase-8.1-item-fencing
phase-8.2-scoped-stage-run
phase-8.3-task-pause-resume
phase-8.4-task-lease-recovery
phase-8.5-worker-resize
phase-8.6-redis-limiter
phase-8.7-flyway
phase-8.8-streaming-io
phase-8.9-ci-tests
phase-8.10-monitoring
```

禁止一次把 Phase 8 全部修改完。

---

# 16. 上线前总验收

准备：

```text
短篇：3话
中篇：20话
长篇：50话+
```

测试：

## A
PROJECT BATCH 全部成功。

## B
只生成第5话，其他话不产生 AI 调用，Project 状态正确。

## C
CHAPTERS：

```text
3,5,8,10
```

只运行目标话。

## D
单页重绘时其他页有 PENDING，也只执行该页。

## E
生图并发 10 时 Pause：

```text
已发送请求允许完成
不再领取新 Item
Task → PAUSED
```

Resume：

```text
同 Task ID
同 Payload
```

## F
Pause 后 restart 仍 PAUSED。

## G
生图中 kill -9：

```text
lease过期
自动接管
已成功结果不重复
旧Attempt不能覆盖
```

## H
两个 Server 同时启动：

```text
不重置对方RUNNING
不清对方Limiter
不重复正式业务结果
```

## I
并发：

```text
5→10→2→8
```

正常收敛。

## J
300页漫画导出：

```text
JVM内存不能随ZIP总大小线性上涨
```

---

# 17. Phase Gate

完成 Phase 8.0 后必须满足：

1. Stage Item 有 Attempt Fencing；
2. 旧 Attempt 永远无法覆盖最新结果；
3. 单页/单话/多话严格按 Scope；
4. 局部生成不会错误完成整个 Project；
5. Pause/Resume 是同一个 Task；
6. PAUSED restart 后不会自动执行；
7. STOP 不会被 Recovery 变回 PENDING；
8. 多实例启动不会重置其他实例的 RUNNING Task；
9. Redis 并发上限热更新不会漂移；
10. Worker 热更新不使用共享 Poison Pill；
11. DB Migration 进入 Flyway；
12. ZIP / 图片处理使用流式 IO；
13. GitHub CI 自动测试；
14. Failure Injection 全部通过；
15. 管理后台可以看到 stale/retry/queue/AI 并发核心指标。

---

# 18. 最终稳定架构

```text
                         MySQL
          ┌───────────────┼─────────────────┐
          │               │                 │
        Task           Pipeline          Business
          │             Stage            Project
          │               │              Chapter
          │          Stage Item          Page
          │          + Attempt           Asset
          │           Fencing              │
          │               │                 │
          └───────┬───────┴────────┬────────┘
                  │                │
               Redis           Fenced Commit
          Queue / Lock /         Transaction
          Lease / Limit             │
                  │                 │
               Workers             OSS
                  │
            AI Text/Image
```

最终执行语义：

```text
Redis 消息允许重复
Task 允许重试
Stage Item 允许重试
AI 请求允许重复

但只有最新合法 Attempt
有权提交最终正式结果
```

---

# 19. 最终原则

当前系统不要追求：

```text
“队列绝对不会重复”
```

应该追求：

```text
调度 = at least once
执行 = 可重试
提交 = fenced + idempotent
最终可见结果 = effectively once
```

这比当前阶段立即切 Redis Streams、Kafka 或重做任务系统更重要。
