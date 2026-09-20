# AIMangaStudio Phase 8 后续修复执行文档

> **已审核并完成代码实施。** 2026-09-20 已按批次 A—E 落地；本节末尾记录本机验证边界与上线前仓库设置。

**Goal：** 在现有任务和 Stage Item 架构上，补齐执行所有权、暂停恢复、页面版本及发布一致性，完成后续生产化收口。

**Architecture：** 保留 MySQL 事实状态、Redis 队列与限流、现有 Task/Stage/Item 模型。先修确定性缺陷，再增加持久化控制意图与执行计划；业务写入统一校验 Task 和 Item 所有权，并通过页面版本 CAS 解决跨 Stage 写竞争。AI 与 OSS 请求始终在数据库事务外执行。

**Tech Stack：** Java 17、Spring Boot 3.3.5、MyBatis-Plus 3.5.7、MySQL、Flyway、Redisson 3.35.0；React 18、TypeScript 5.6、Vite 5；JUnit 5、Mockito，后续补 MySQL/Redis Testcontainers。

## 1. 基线、依据和本轮核对结果

- 来源：[AI漫画系统可行性——后续深查](chatgpt-conversation://6a9e6d58-0ad0-83ee-a98b-68bd9321671c)，已读取最新完整回复，包含三项原标记 P0、第二至第二十节及第二十一节的八项补充问题。
- 目标代码基线：`main` 提交 `dd6ca2604e7225c00bdfa0dca7728e5b6d400c1c`。
- 本轮本地 `v2` 项目快照与该提交根目录 tree 相同：`0a66636f2669043475413267eed0ffb7bf8a0518`。因此可以用当前工作区核对这份基线；不能把本地旧分支名称当成远程 main 的目录结构。
- 文中所有路径均相对于**项目根目录**：`docs/`、`frontend/`、`server/`、`.gitignore`、`README.md`。远程 main 不增加 `v2/` 层级。
- 本轮只新增本文件，未修改业务代码、迁移、配置或远程分支。

### 1.1 对引用审查的两项必要纠正

**前端字段缺失已确认，但“当前必然构建失败”不成立。** `TaskVO` 缺少 `retryCount`、`maxRetryCount`、`lastError`；`TaskMonitor` 当前使用的是 Ant Design 表格 `dataIndex` 字符串，而非引用回复所述的直接 `t.retryCount`/`t.lastError`。本轮实际执行 `npm run build`，TypeScript 与 Vite 均通过。保留类型补齐任务，降为 P1 契约缺口，不能用它宣称当前构建失败。

**Seed 迁移不能直接沿用 `V2__seed_defaults.sql`。** 当前已存在 `V8_0_1` 至 `V8_0_3`；新增低版本 V2 在已有库上可能不被正常应用。本计划统一新增 `V8_1_*` 版本，实施时按实际最新迁移递增，不修改已发布迁移 checksum。

### 1.2 证据边界

空 Scope 回退全量、JSON 列空字符串、Handler 重复 force reset、未绑定 Task 所有权的 Item commit、STOPPING 续租过滤、admission 前重置 Item、整包内存导出等，均已从当前代码核对到。并发时序、MySQL JSON SQL、Redis 多实例限流、暂停快速恢复、迁移升级等，需要后续真实数据库/Redis与故障注入测试，本文不将它们描述为本轮已经运行复现。

## 2. 全局约束与审核决策

1. 保留当前目录结构和现有业务功能，不更换 Redis Streams，不重写整个流水线。
2. 一个业务请求仍对应现有粒度的 Task；禁止改成每页创建一个 Task。
3. 保留 Task 状态数值：0排队、1运行、2成功、3失败、4部分失败、5停止、6停止中、7暂停。项目控制意图使用独立字段，避免将新的控制意图混入这些数值。
4. 数据库所有时间判断优先使用数据库时间。网络请求、等待 AI、上传 OSS、生成大型 ZIP 不放入数据库事务。
5. 业务结果、业务运行/失败状态、生成记录和计划单元状态都受所有权校验。旧 Worker 失去所有权后只能退出并记录拒绝，不能写 FAILED、清空新结果或创建后继任务。
6. 暂停/正常停止采用 drain：停止领取新单元，允许已合法领取的单元完成短事务提交。租约撤销或硬超时后，旧请求返回不得提交。
7. Resume 保留同一 Task、原始 payload、既定目标范围、已成功结果和累计统计；不再次执行 force reset。
8. 用户手动重试和自动恢复分别定义：前者开启新一轮预算，后者保留已有重试次数。成功单元仅在新的显式强制生成请求中重画。
9. Scoped 请求的同步、重置、执行、统计使用同一目标集合。真正删除不存在业务对象的 orphan，依据数据库真实存在性处理，不能把局部目标集合当成全项目存活集合。
10. 外部下载继续复用 `RemoteImageFetcher` 的 SSRF、MIME、大小限制；不以修复导出为由绕过该入口。
11. 已有未提交文件不纳入本批修改；每次提交列出精确路径，检查整个提交而非仅检查工作区。
12. 本文件审核通过只授权后续实施范围；本文生成阶段不提交、不推送、不改数据库。

### 2.1 需要在审核时决定的范围

| 决策 | 本文建议 | 对范围的影响 |
|---|---|---|
| 执行计划持久化 | `task` 增加计划元数据，新增 `task_plan_unit` | 增加一张表；解决恢复重复 force reset与任务级累计统计 |
| Pause/Resume | 项目和任务各自保存 durable pause 意图及版本 | 增加字段与返回信息；不新增 Task 状态数值 |
| 页面写竞争 | `page.image_revision` + 输入 `script_version` CAS | 所有成品图写入入口统一升级 |
| 发布包兼容 | 增加原始对白/旁白和内容版本；保留旧字段 | 同时验证 APP 导入端；不直接强制改 schema 主版本 |
| GitHub CI | 新增 `.github/workflows/ci.yml` | **这是根目录结构的明确新增例外**。若不批准新增 `.github/`，任务 15 的 GitHub Actions 部分暂不实施，不能宣称 CI 已完成 |
| 批量导出 | 新增 EXPORT Task，产物在临时文件/存储中生成 | 前端改为任务进度与下载产物，取消批量接口的 `byte[]` 同步响应 |

## 3. 问题覆盖表

以下编号是本文件固定编号，避免继续沿用上轮报告编号造成混淆。

| 编号 | 来源问题 | 本文级别 | 执行任务 |
|---|---|---|---|
| R01 | 前端 TaskVO 重试字段缺失 | P1，构建阻断结论已纠正 | 1 |
| R02 | 显式空 Scope 在 Service 退化为全项目 | P0 | 2 |
| R03 | JSON `result_ref` 写入非法空字符串 | P0 | 3 |
| R04 | Resume 重跑 force 初始化，重复 AI 费用 | P1 | 7 |
| R05 | 运行/失败业务状态没有完整 fencing | P1 | 4 |
| R06 | Task 所有权与 Item 所有权未绑定 | P1 | 4 |
| R07 | STOPPING 不续租，90秒误判存活 Worker | P1 | 5 |
| R08 | 项目暂停意图未持久化，遗漏排队/SPLIT/ASSET | P1 | 6 |
| R09 | drain期间 Resume 导致随后又被置 PAUSED | P1 | 6 |
| R10 | 单页 LAYOUT 同步/重置了全项目 Item | P1 | 8 |
| R11 | Task admission 失败前已修改 Item | P1 | 7 |
| R12 | 本话 SCRIPT regenerate 未尊重 chapterId/force | P1 | 9 |
| R13 | 重建/删除 Page 留下旧 Item，污染统计 | P1 | 9 |
| R14 | 发布 Gate 未校验图/对白的脚本版本 | P1 | 11 |
| R15 | IMAGE 与后处理跨 Stage 覆盖同一 Page | P1 | 10 |
| R16 | 手动重试未恢复 Item retry 预算 | P1 | 7 |
| R17 | Redis marker失败后的重入队异常退出 Worker | P1 | 12 |
| R18 | User Permit TTL 与 Task 实际执行时限不一致 | P1 | 13 |
| R19 | ZSET 限流不是 Lua 原子，注释失实 | P1 | 13 |
| R20 | 批量导出汇聚全部 byte[]，吞 ZIP 写失败 | P1 | 14 |
| R21 | Flyway 未接管默认配置/风格 seed | P1 | 15 |
| R22 | 未跟踪 GitHub CI，前端缺 lint/test | P1 | 15 |
| R23 | stop read→updateById 覆盖并发终态 | P1 | 5 |
| R24 | Worker 缩容后的 Handle 未移除 | P2 | 12 |
| R25 | Stage/Item 先查后插，唯一键竞争异常 | P1 | 7 |
| R26 | 通用 `/api/tasks` 绕过专用业务校验 | P1 | 7 |
| R27 | 项目无在跑任务也长期显示 GENERATING | P2 | 11 |
| R28 | 非PNG全部以JPG扩展名导出 | P2 | 11 |
| R29 | staleRejected JVM计数不能跨实例观测 | P2 | 15 |
| R30 | 单线程心跳调度在DB抖动时积压 | P2 | 5、15 |

## 4. 执行批次和共同验收方法

| 批次 | 任务 | 完成后必须具备的结果 |
|---|---|---|
| A 确定性修复 | 1—3 | 类型契约补齐；空 Scope 不执行任何单元；JSON重置可在MySQL运行 |
| B 执行控制 | 4—6 | 失去Task所有权不能写任何业务状态；STOPPING正确drain；快速Pause/Resume无失联 |
| C 计划与页面生命周期 | 7—10 | admission无副作用；Resume不重画成功页；本话脚本和单页布局严格范围；图像CAS |
| D 发布与运行恢复 | 11—13 | 当前脚本对应当前图/对白；Redis故障后Worker继续消费；限流可续租且原子 |
| E 生产化闭环 | 14—15 | 大批导出内存有界；新旧库迁移通过；CI和观测形成持续验证 |

每个任务执行以下闭环：

- [ ] 创建下文列出的回归测试，运行并记录修复前失败；对于类型补齐等当前不失败的契约任务，不伪造红灯。
- [ ] 实施最小范围修改；新增字段与表走新迁移。
- [ ] 运行该任务专项测试；并发测试使用 latch/barrier 或可控时钟，不依赖随机睡眠。
- [ ] 运行受影响的编译/构建，记录命令、结果、测试数和环境。
- [ ] 复核 diff：没有输入目录搬迁，没有夹带其他未提交改动。
- [ ] 单独提交该任务的精确路径，或将紧密依赖任务合为一个可审查提交；远程推送遵循当时用户授权。
- [ ] 回填第 8 节记录，进入下一任务。

默认验证命令相对于项目根目录：

```bash
cd frontend
npm ci
npm run build
```

```bash
cd server
mvn -B -Dtest=StageRunScopeTest,ConcurrentStageRunnerTest test
mvn -B verify
```

后续新增测试类按任务修改 `-Dtest` 清单。集成测试需真实 MySQL/Redis 容器；不能以 H2 替代 JSON、行锁、MySQL时间和唯一键的验收。测试用 AI/OSS stub，不调用付费接口。

## 5. 逐任务实施要求

### 任务 1：补齐前后端 TaskVO 契约（R01）

**文件：** 修改 `frontend/src/api/tasks.ts`；核对 `frontend/src/pages/admin/TaskMonitor.tsx`、`server/src/main/java/com/aimanga/v2/dto/TaskVO.java`、`repository/TaskMapper.java` 和 `service/TaskService.java` 的 `toVO`。

- [ ] 在前端增加与后端 DTO/实际 JSON 一致的字段；明确列表和详情都返回它们。

```ts
retryCount: number;
maxRetryCount: number;
lastError: string | null;
```

- [ ] 后端对旧数据中的 nullable 计数统一输出默认值 0；`maxRetryCount` 使用有效配置值，避免前端得到 undefined。
- [ ] 将重试列改为类型化读取（例如 `render: (_, task) => task.retryCount`），由类型检查约束字段名；不要为了通过编译转为 `any`。
- [ ] 运行前端构建并验证管理员列表和详情中重试次数/最后错误可见。此任务不添加仅重复interface内容的单元测试。

**验收：** 前后端字段一致、构建通过；报告不再记“基线当前编译失败”。

### 任务 2：彻底封闭空 Scope（R02）

**文件：** 修改 `pipeline/PipelineStageService.java`、`pipeline/ConcurrentStageRunner.java`；检查 `pipeline/StageRunScope.java`、`repository/PipelineStageItemMapper.java`；新增 `pipeline/PipelineStageServiceScopeTest.java`，扩展 `ConcurrentStageRunnerTest.java`。后端路径前缀均为 `server/src/main/java/com/aimanga/v2/`，测试前缀均为 `server/src/test/java/com/aimanga/v2/`。

**接口语义：** 全量仅走 `getPendingItemIds(...)` 和显式 `StageRunScope.all()`；`getPendingItemIdsInScope(...)` 是严格有界入口，null视为调用错误，空集合返回空结果。这个规则比引用对话中“null仍表示all”的示例更严格，能防止漏传参数导致扩大范围。

```java
if (businessIds == null) {
    throw new IllegalArgumentException("scoped businessIds must not be null");
}
if (businessIds.isEmpty()) {
    return List.of();
}
return itemMapper.selectPendingIdsInScope(
        projectId, stageType, businessType, businessIds, limit);
```

- [ ] 将上述语义同时贯彻候选查询、scope统计、reset和SQL Mapper调用；不生成 `IN ()`。
- [ ] Runner 对显式空范围提前返回零统计，不回收其他范围 RUNNING、不更新全项目 Stage 为 SUCCESS、不提交 Worker任务。
- [ ] 审计所有 `getPendingItemIdsInScope` 调用；全量调用改为单独全量方法。

**必须加入的回归断言：**

```java
assertThat(service.getPendingItemIdsInScope(
        1L, "IMAGE", "PAGE", List.of(), 64)).isEmpty();
verify(itemMapper, never()).selectPendingIds(anyLong(), anyString(), anyInt());
verify(itemMapper, never()).selectPendingIdsInScope(
        anyLong(), anyString(), anyString(), any(), anyInt());
```

Runner端另测：项目存在其他页PENDING，执行 `StageRunScope.pages(List.of())` 后 claim=0、processor=0、其他Item状态不变。只有测试Scope对象本身不算完成。

### 任务 3：合法清理 JSON 与 attempt 残留（R03）

**文件：** 修改 `pipeline/PipelineStageService.java`；新增 `pipeline/StageItemResetIntegrationTest.java`；检查所有 Item reset 方法及迁移的 `result_ref JSON`。

- [ ] `resetSuccessfulItemsByBusiness` 改为 SQL NULL，不写 Java字符串 `"null"` 或空字符串。

```java
.set(PipelineStageItem::getResultRef, null)
.set(PipelineStageItem::getErrorMessage, "")
.set(PipelineStageItem::getFinishTime, null)
.set(PipelineStageItem::getAttemptToken, null)
.set(PipelineStageItem::getClaimedAt, null)
```

- [ ] 统一reset清理字段；保留独立的force标记语义，任务7实施后迁移至计划单元参数，避免结果与输入参数长期共用 `result_ref`。
- [ ] MySQL容器中插入SUCCESS Item及合法JSON结果，实际执行重置，再用SQL确认 `result_ref IS NULL`、状态PENDING、finish_time为空。
- [ ] 覆盖空范围和范围外SUCCESS不变；自动恢复不能顺便清掉重试预算。

**验收：** 实际MySQL SQL运行通过，没有 `Invalid JSON text`；不能只验证Wrapper或mock返回值。

### 任务 4：统一 Task/Item 双重所有权与业务状态 fencing（R05、R06）

**文件：** 修改 `task/TaskRuntime.java`、`task/TaskRunner.java`、`pipeline/StageItemExecution.java`、`pipeline/StageItemCommitService.java`、`pipeline/ConcurrentStageRunner.java`、`model/PipelineStageItem.java`、`repository/TaskMapper.java`、`repository/PipelineStageItemMapper.java`；修改 `PageGenerationService`、`SheetTaskHandler`、`AssetRefTaskHandler`、`LayoutTaskHandler`、`ScriptTaskHandler`、`BatchTaskHandler`、`PageTaskHandler`、`PostProcessTaskHandler`、`SplitTaskHandler`、`AssetTaskHandler` 的正式写入入口。新增 `task/TaskExecutionOwner.java`、`V8_1_0__item_task_ownership.sql`、`pipeline/TaskItemOwnershipIntegrationTest.java`，扩展已有fencing故障注入测试。

**产生接口：**

```java
public record TaskExecutionOwner(Long taskId, String claimToken) {}
public record StageItemExecution(PipelineStageItem item,
                                 String attemptToken,
                                 TaskExecutionOwner owner) {}
```

计划单元ID由任务7进一步加入上下文。禁止跨线程依赖ThreadLocal传所有权，必须通过参数显式传递。

- [ ] `TaskRuntime` 提供 `owner()` 和本地ownershipLost标志；检查数据库 token、状态和lease有效性，失效与用户stop用不同信号区分。
- [ ] Item增加 `owner_task_id BIGINT NULL`、`owner_task_claim_token VARCHAR(64) NULL`；领取时在校验Task的短事务内写入，提交时同时校验这组关联和execution上下文。Task控制意图检查与Item claim不能仅靠前一次无锁读取。
- [ ] heartbeat返回0或明确发现所有权失效时，停止新请求；心跳SQL异常按任务5规则处理，不能无限只告警继续。
- [ ] 短事务统一锁序：涉及项目控制时先project，再task，再stage_item，最后业务page/asset；同类批量行按ID升序。所有恢复/reset路径检查是否与此相反，避免死锁。
- [ ] `commitFenced` 同事务锁Task和Item，要求Task token一致、lease未过期、status为RUNNING或合法drain的STOPPING；Item为RUNNING且token一致，才能执行businessWrites。
- [ ] Task已被WatchDog撤销但Item token尚未变化，也必须拒绝。pause意图不会拒绝已合法领取的in-flight提交；新领取在任务6拦截。
- [ ] Runner不再只凭project+stage Redis锁全量 `resetRunningItems`。回收仅处理数据库确认owner Task已失效/终态的Item；当前合法所有权的Item不被回收，空范围尤其不触碰其他Item。失去所有权的Runner及时停feed并有界收尾释放Stage锁，迟到AI由commit校验拒绝。
- [ ] 提供统一的owned运行标记/失败提交入口；Page/Asset的GEN_RUNNING、GEN_FAILED、错误信息与Item状态写入必须同时校验所有权。`markItemFailed`本身已有Item token过滤，不能因此漏掉业务表写入。
- [ ] `StaleCommitRejectedException`单独捕获并传播；不能进入普通失败catch后写业务FAILED。任何异常后的生成记录同样不能绕过校验。
- [ ] SPLIT/ASSET没有完整Item时，先采用Task ownership短事务保护所有章节重建、资产merge和后继Task创建；后继创建必须幂等。
- [ ] 不提供继续可调用的无所有权正式写入口；迁移所有调用方后删除或限制旧接口。

**故障注入：** A阻塞在AI stub；撤销Task A token但保留Item A token；B领取并成功；释放A分别返回成功和异常。最终URL、业务状态、error、生成记录及Item都只含B结果，A的businessWrites调用次数为0。另测合法STOPPING可保存in-flight结果。

**边界：** 双fencing保证可见结果不会被旧Worker污染，不保证外部AI供应商只收费一次；对已发出但无结果的请求是否可恢复费用，必须依据供应商幂等能力另行验证。

### 任务 5：STOPPING 续租、终态 CAS 与心跳可靠性（R07、R23、R30）

**依赖：** 任务4。

**文件：** 修改 `repository/TaskMapper.java`、`service/TaskService.java`、`task/TaskRunner.java`、`task/TaskWatchDog.java`、`task/TaskRuntime.java`；扩展 `task/TaskRunnerTest.java`，新增 `task/TaskLifecycleIntegrationTest.java`。

- [ ] heartbeat和合法drain进度更新允许 `status IN (1,6)`，始终携带claim_token。
- [ ] STOPPING僵尸查询使用 `lease_until <= NOW()`；兼容旧lease为空记录的处理作为一次显式迁移/降级规则，不能让新记录一直依赖固定90秒heartbeat。
- [ ] WatchDog的回收UPDATE重新校验token、状态和过期条件，防止扫描后heartbeat续租仍被旧扫描结果撤销。
- [ ] stop采用source-state CAS，不再普通 `updateById`；终态不能被stop覆盖。正常停止优先于handler返回SUCCESS：RUNNING→STOPPING成功后，drain finish落STOPPED。

```sql
UPDATE task SET status = 6
WHERE id = :id AND status = 1;

UPDATE task SET status = 5, end_time = NOW()
WHERE id = :id AND status IN (0,7);
```

这两条为状态转换核心；完整实现同时按状态清理lease/token并保证CAS失败时重读。不要把未确认身份的token为空条件当成所有权校验。

- [ ] retry、resume、delete也使用合法source状态CAS，防止两次人工retry互相重置正在执行任务。
- [ ] 心跳scheduler每次运行捕获DB异常，避免ScheduledFuture被异常永久取消；单任务调度不重入；线程数有界并可配置；应用关闭时取消Future并关闭scheduler。
- [ ] 心跳连续失败不能确认有效租约时，超过本地最近有效lease截止立即标记ownershipLost；业务提交仍以数据库校验兜底。

**回归：** AI等待超过原90秒且STOPPING持续续租，不被WatchDog误停；撤销lease后迟到提交被拒绝；finish与stop用barrier并发，最终仅合法终态；模拟一次heartbeat DB异常，下一轮仍执行；多个慢heartbeat不拖死全部任务。

### 任务 6：持久化 Pause/Resume 意图与 drain 握手（R08、R09）

**依赖：** 任务4—5。

**文件：** 修改 `model/Project.java`、`model/TaskEntity.java`、相应Mapper、`controller/ProjectController.java`、`service/TaskService.java`、`pipeline/PipelineStageService.java`、`ConcurrentStageRunner`、`SplitTaskHandler`、`AssetTaskHandler`、Task claim及恢复扫描；新增 `service/PipelineControlService.java`；更新 `dto/ProjectVO.java`、`dto/TaskVO.java` 和前端对应API/工作台；新增 `V8_1_1__pipeline_control_intent.sql` 与 `pipeline/PipelineControlIntegrationTest.java`。

**建议新字段：** project/task各有 `pause_requested TINYINT NOT NULL DEFAULT 0`、`control_version BIGINT NOT NULL DEFAULT 0`。Project原生产status继续表示内容完成状态。项目暂停与单Task暂停各自独立：effectivePause为两者OR；单Task resume不能绕过项目pause。

**产生接口：** `PipelineControlService.pauseProject(projectId)`、`resumeProject(projectId)`、`pauseTask(taskId)`、`resumeTask(taskId)`，内部在短事务内锁控制行并递增版本；返回当前意图、版本和仍在drain的数量。

- [ ] 项目pause先保存意图，再把相关PENDING Task置PAUSED；RUNNING Task不再领取新单元，保留token完成drain。Stage PAUSED只作为展示状态，不作为唯一控制来源。
- [ ] Task claim在同事务或带project条件的SQL中尊重effectivePause；Stage start、Item claim、SPLIT/ASSET分包发起前也检查。暂停之后到来的新Task允许保存为PAUSED但不得enqueue执行。
- [ ] 快速resume先持久化意图，再对已PAUSED Task执行恢复；仍RUNNING且已进入drain的旧Runner在收尾时重读意图版本。
- [ ] drain收尾与resume锁同一project/task行：若effectivePause仍true落PAUSED；若已false，把同Task置PENDING并after-commit入队；禁止再按旧内存paused标志覆盖新resume意图。
- [ ] STOPPING具有更高优先级：用户stop后不能被项目resume重新启动。
- [ ] Redis/SSE发布和enqueue在事务提交后执行；失败由DB扫描补偿，接口返回持久化意图已接受，不要求前端反复点resume。
- [ ] 前端根据durable意图显示“暂停中/已暂停/继续中”，同时保持现有Task状态7处理；不新增未同步的数字状态。

**时序测试：** PENDING时pause→随后Worker claim失败；SPLIT/ASSET下一包不发请求；AI阻塞→pause→resume→释放AI→只点一次resume也继续；交错两次pause/resume最终服从最大control_version；单Task pause在项目resume后仍暂停；STOPPING不被恢复。

### 任务 7：admission + 一次性执行计划 + 手动重试语义（R04、R11、R16、R25、R26）

**依赖：** 任务4—6；任务8—10消费这里的目标集与版本信息。

**文件：** 修改 `service/TaskService.java`、`controller/TaskController.java`、`controller/PageController.java`、`pipeline/MaterialGenerationService.java`、各业务创建Task入口和Handler、`task/TaskRuntime.java`、`pipeline/PipelineStageService.java`；新增 `service/TaskPlanningService.java`、`model/TaskPlanUnit.java`、`repository/TaskPlanUnitMapper.java`；新增 `V8_1_2__task_execution_plan.sql` 与 `pipeline/TaskPlanRecoveryIntegrationTest.java`、`service/TaskAdmissionIntegrationTest.java`。

**持久化契约：** task新增 `plan_version INT NOT NULL DEFAULT 0`、`plan_initialized_at DATETIME NULL`、`plan_snapshot JSON NULL`。新增task_plan_unit保存固定目标与终态，不能仅靠全项目Stage统计恢复Task进度。

```sql
CREATE TABLE task_plan_unit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  plan_version INT NOT NULL,
  stage_type VARCHAR(32) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  business_id BIGINT NOT NULL,
  input_snapshot JSON NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  attempt_token VARCHAR(64) NULL,
  result_ref JSON NULL,
  error_message TEXT NULL,
  UNIQUE KEY uk_task_plan_business
    (task_id, plan_version, stage_type, business_type, business_id),
  KEY idx_task_plan_status (task_id, plan_version, status)
);
```

建表字符集、时间字段、引用清理由实施时统一沿用现有schema规范；task删除时在同事务清理units。`input_snapshot`保存force、colorMode、参数、scriptVersion和后处理source image revision，不能只保存页面ID。该迁移同时为Item增加 `plan_unit_id BIGINT NULL`，通过任务4的owner关联与unit的task_id/plan_version校验唯一计划归属。

**产生接口：** `TaskPlanningService.admitAndPlan(projectId, chapterId, taskType, payloadJson)`；`TaskRuntime.restore(total, success, failed)`；计划单元claim/final commit携带unitId和planVersion。这里的接口名称是后续任务的固定契约。

- [ ] admission前完成身份、project/chapter/page归属、业务参数、预检；不能先reset或create Item。
- [ ] 项目行锁/数据库统一锁序下执行active检查、Task和计划持久化、目标Item初始化；事务提交后enqueue。复用相同请求返回原Task，不重新初始化计划；不同payload返回409且没有任何Item/计划副作用。
- [ ] 通用 `/api/tasks` 对业务Task委托同一planning入口；必须复用专用API校验，禁止当前直接save绕过。MOCK保留管理员权限独立入口；系统链式Task同样走幂等计划。
- [ ] `plan_snapshot`是受服务器控制的执行输入，不覆写用户原payload；`planInitialized=true`不能单独放在客户端可传payload并受客户端决定。
- [ ] 首次计划事务内仅一次force/reset；Handler run仅加载计划，不再重复forceReset或resetFailed。Resume和WatchDog恢复都消费原unit集合。
- [ ] Runner候选限定为本Task、本planVersion未终态units对应的Item；Item不能被另一活跃计划reset。admission遇到目标正在其他计划运行，原子拒绝或明确等待，不能部分初始化。
- [ ] business commit与计划unit终态、Item终态同事务；重复/迟到回调不增加统计。进度从本planVersion units聚合，成功/失败不能因runtime.begin清零。
- [ ] 手动retry仅对本Task失败units开启新预算，保留成功units与原输入；planVersion递增或显式新retry轮次，旧attempt不得写新轮次。新的“全部强制生成”是新请求，不偷换retry语义。
- [ ] 自动恢复保留retry_count，仅回收已失效ownership的运行unit。阶段重试预算和Task执行重试预算分别记录，定义“maxRetry=3为首次执行后的最多3次重试”。
- [ ] Stage getOrCreate/createItems改为唯一键幂等插入后重查；吞掉的仅限目标唯一键冲突，其他SQL错误必须上抛，不能用无边界 `INSERT IGNORE` 掩盖字段/数据错误。
- [ ] 新迁移上线时暂停领取旧PENDING/PAUSED Task，按原payload建立兼容计划；已有SUCCESS计为完成而非force重画。若无法从旧Task恢复精确范围，标记需要人工处理，不默认全项目。

**关键验收：** forceImage=true共10页，6页成功后pause/resume；同Task/同planVersion，成功6页无新AI调用，余4页执行，统计最终10/10。初始化事务前后分别注入崩溃，重启后计划不会第二次force。409请求前后目标所有表内容不变；两个同请求并发只产生一个active Task；手动retry恢复预算，自动恢复不清预算。

### 任务 8：单页 LAYOUT 全链路同范围（R10）

**依赖：** 任务2、7。

**文件：** 修改 `pipeline/LayoutTaskHandler.java`，核对 `controller/PageController.java`、`service/TaskPlanningService.java`；新增 `pipeline/LayoutTaskScopeTest.java`。

- [ ] 在planning阶段解析并验证pageId/chapterId，产生唯一 `targetPageIds`；单页就是 `List.of(pageId)`，chapter只查本章，project才查全部。
- [ ] sync、create、stale SUCCESS reset、FAILED reset、force、Runner、任务进度都消费同一计划目标；不再 `syncItems(project, chapterId, ...)` 忽略pageId。
- [ ] scoped任务无候选时结束本Task，调用项目Stage聚合刷新，不能无条件 `markSuccess`整个Stage。
- [ ] empty targets采用任务2空范围语义，不重新扩大为all。

**验收数据：** 目标页A、其他话B缺布局、其他话C旧SUCCESS、其他话D失败；仅A做单页重布局。B/C/D Item不新增、不reset、不执行；项目Stage状态按整体真实状态重算，Task总数仅1。

### 任务 9：本话脚本重生成及 Page 删除/重建清理（R12、R13）

**依赖：** 任务4、7；与任务10的Page并发策略联动。

**文件：** 修改 `controller/ChapterController.java`、`pipeline/ScriptTaskHandler.java`、`pipeline/StageRunScope.java`、`pipeline/PipelineStageService.java`、`service/PageService.java`、`pipeline/split/ChapterRebuildService.java`；新增 `pipeline/ScriptRegenerationIntegrationTest.java`、`pipeline/PageLifecycleIntegrationTest.java`。

- [ ] regenerate-script在admission指定 `chapterId` 和服务器确定的 `forceScript=true`，仅重置/计划目标CHAPTER；普通首次SCRIPT与显式重生成分开。
- [ ] 使用 `StageRunScope.chapters(List.of(chapterId))`；如果类中不存在该工厂，增加严格有界工厂（空集合保持空），不构造all替代。
- [ ] 仅forceScript跳过 `chapter.status >= SCRIPT_READY` 幂等条件；非force不能重复收费。统计与reset仅本话，不能处理其他FAILED/PENDING话。
- [ ] 本话脚本重生成不重复触发全项目自动后继链。后继由计划目标和业务意图决定；同一计划的后继最多创建一次。
- [ ] writePages事务内保存旧pageId集合，删除对应PageAssetRef/TextElement与PAGE型Item；清理包含LAYOUT、IMAGE、COLORIZE、CLEAN、REPAINT等全部Page阶段。
- [ ] 删除/重建Page前检查运行计划；建议遇到同话活跃Page任务返回409，让用户先停止，不在AI返回前直接无声删除运行对象。
- [ ] 定义 `cleanupPageStageOrphans(projectId)` 为按数据库真实Page存在性清理，而不是局部列表；使用NOT EXISTS并同时限制project/businessType，覆盖当前无Page的空项目。

```sql
DELETE i FROM pipeline_stage_item i
WHERE i.project_id = :projectId AND i.business_type = 'PAGE'
  AND NOT EXISTS (
    SELECT 1 FROM page p
    WHERE p.id = i.business_id AND p.project_id = i.project_id
  );
```

- [ ] 清理引用已删除Page的计划单元，记明确终态/删除原因，而不是作为成功；刷新相关Stage与项目状态。
- [ ] Page删除、Chapter删除/重建都复用此生命周期入口；旧Attempt对已删除Item/Page的提交应被拒绝，不重新创建旧行。

**验收：** SCRIPT_READY目标话仍真正产生新脚本，其他话0次AI；旧Page对象及引用已清理，新Page对应Item无旧残留；scoped操作保留其他真实话Item；AI迟到不复活旧页；在跑冲突返回409且不删除任何记录。

### 任务 10：Page 图像revision与脚本输入版本 CAS（R15）

**依赖：** 任务4、7。

**文件：** 修改 `model/PageEntity.java`、`repository/PageMapper.java`、`pipeline/PageGenerationService.java`、`pipeline/LayoutGenerationService.java`、`pipeline/PostProcessService.java`、相关Handler和Page编辑入口；新增 `V8_1_3__page_image_revision.sql` 与 `pipeline/PageImageRevisionIntegrationTest.java`。

- [ ] 增加 `image_revision BIGINT NOT NULL DEFAULT 0`；IMAGE/COLORIZE/CLEAN/REPAINT计划捕获source revision和scriptVersion，提交时同时校验。
- [ ] 业务UPDATE使用显式列，不用陈旧整个Page实体 `updateById` 覆盖其他新字段。

```sql
UPDATE page
SET generated_image_url = :url,
    image_script_version = :inputScriptVersion,
    image_revision = image_revision + 1,
    generate_status = 2
WHERE id = :pageId AND project_id = :projectId
  AND script_version = :inputScriptVersion
  AND image_revision = :expectedImageRevision;
```

- [ ] UPDATE=0时同事务回滚业务、生成记录和SUCCESS标记，计划unit记录“内容版本冲突”；不要自动套用旧后处理到最新图，避免无提示重复收费。
- [ ] 所有替换成品URL入口（包含手工选取历史图/上传成品）都推进image_revision。后处理必须在发请求前确认source URL/revision仍与计划一致。
- [ ] LAYOUT结果也校验输入scriptVersion；文本层保存同样校验输入脚本版本，防止编辑期间迟到布局/对白覆盖最新脚本。
- [ ] 历史图若与当前脚本版本无可靠对应，不能伪造imageScriptVersion=current；保留为历史预览或要求明确重新生成。
- [ ] Page脚本修改与CAS事务相互序列化，保留旧图便于预览，但旧图不视为当前可发布成品。

**验收：** PAGE和CLEAN同revision同时发起，用barrier控制两种提交顺序；仅一个业务CAS成功，另一个冲突且无SUCCESS生成记录。AI执行中修改scriptVersion，旧图与旧布局均不能作为新脚本结果提交。不同Page仍可并发执行。

### 任务 11：发布 Gate、文本fallback、完成语义与真实格式（R14、R27、R28）

**依赖：** 任务9—10；批量导出任务14复用这里的快照。

**文件：** 修改 `pipeline/ProjectCompletionService.java`、`pipeline/PublicationService.java`、`pipeline/TextLayerService.java`、`dto/export/ComicManifestPage.java`、`dto/export/ComicManifest.java`、`pipeline/ComicImporterService.java`；核对APP导入映射，修改 `model/app/ComicPageEntity.java` 与导入持久化字段所需新迁移；扩展 `ProjectCompletionServiceTest`、`ComicImporterServiceTest`，新增 `PublicationServiceTest` 和发布并发集成测试。

- [ ] 定义共享的current-image判断：GEN_SUCCESS、URL非空、imageScriptVersion与scriptVersion均有可靠值且相等。NULL不能默认变成相等而掩盖旧记录。
- [ ] 只有图像当前的Page才计入COMPLETE/DONE；Project非全部完成时，有合法执行任务才显示GENERATING；无执行则用现有READY/PREPARING/PARTIAL，不把PENDING或PAUSED的展示当成正在跑AI。
- [ ] 含对白/旁白的Page发布必须有文本层数据且textLayoutVersion==scriptVersion；正文存在但elements为空也不通过。完全无文字的Page允许无层；验证元素文本确实覆盖当前正文，不能只信版本数字。
- [ ] 用户修改文本后设置文本层过期；自动同步须调用既有同步策略，不无声丢弃用户排版。validate返回具体话/页及修复原因，不能偷偷标成已同步。
- [ ] manifest保留旧四字段，新增正文fallback与内容版本（具体字段：`dialogue`、`narration`、`scriptVersion`、`imageScriptVersion`、`textLayoutVersion`）；dialogue导出为JSON数组，而不是二次编码的字符串。

```json
{
  "pageNo": 1,
  "imageUrl": "https://example.invalid/page.webp",
  "filePath": "第1话/第1页.webp",
  "textLayer": {"schemaVersion": "comic-text-layer-1.0", "elements": []},
  "dialogue": [{"speaker": "甲", "text": "你好"}],
  "narration": "清晨。",
  "scriptVersion": 2,
  "imageScriptVersion": 2,
  "textLayoutVersion": 2
}
```

上例仅展示manifest结构，不是有效发布页：有文字但elements为空应被Gate拒绝。

- [ ] APP导入支持新包fallback，旧包仍可读；已有文本层优先使用，但正文内容不能因schema解析失败丢失。升级schema版本前验证消费者，不能仅改生产端。
- [ ] 导出采用短事务读取一致的PublicationSnapshot并验证，后续下载/ZIP写出只用该快照，不在每页重新读取可变化的DB状态。不得把整个流式导出放入长事务。
- [ ] 图片存储URL需不可变；若同URL内容可覆写，则导出快照还要固定对象版本/复制产物。当前版本Gate不能单独保证同URL字节一致。
- [ ] `filePathOf`改为依据经过验证的实际MIME映射png/jpeg/webp/gif/avif等允许格式；URL query与无扩展名不影响格式判断。先取得格式再生成manifest路径，下载字节与扩展名对应；不支持格式明确拒绝。

**验收矩阵：** imageVersion旧→ERROR；文本version旧→ERROR；有对白无层→ERROR；无正文无层→允许；当前图和当前层→允许；旧包兼容、新包fallback导入完整；URL `.png?signature=...`、真实WEBP正确命名；导出过程中修改正文，新旧两套内容不能混进一个包；仅第三话完成且无在跑Task，项目不是DONE或GENERATING。

### 任务 12：Redis 故障下 Worker 存活与扩缩容收尾（R17、R24）

**文件：** 修改 `task/TaskWorkerPool.java`、`task/TaskQueue.java`、`task/PendingTaskRecoverScheduler.java`，核对 `pipeline/AbstractStageWorkerPool.java`；新增 `task/TaskWorkerPoolRecoveryTest.java` 与Redis重启集成测试。

- [ ] removeMarker失败只记录，不在catch里调用可能再次失败的enqueueDelayed后退出；拿到taskId后继续尝试DB claim，Redis marker只是去重优化。
- [ ] 外层为poll、marker、缩容返队、permit、执行、释放均建立异常边界；异常不得使Worker永久减少，不能无限吞异常导致日志风暴。
- [ ] Redis不可用导致无法获取permit时不执行超额任务，DB保留PENDING，由恢复扫描在Redis恢复后重新入队；不要为容错绕开限流。
- [ ] poll异常采用有上限退避并可中断，避免无延迟循环占CPU；不在生产任务线程执行无限重试。
- [ ] WorkerHandle在finally注册退出状态并从workers移除；期望数量、存活数量、draining数量分开计算，resize操作同步，避免旧线程收尾覆盖新线程记录。
- [ ] 停机/缩容返队失败时依赖DB补偿保证可恢复；不得清空其他实例队列、marker或许可。

**验收：** removeMarker和enqueueDelayed都失败仍不退出worker；Redis停30秒再恢复后线程数达期望且PENDING能消费；5→2→6→1→5反复执行，workers集合只含存活/明确draining的Handle，任务没有双执行；长任务缩容能drain并最终移除Handle。

### 任务 13：原子限流、Permit续租与Task时限一致（R18、R19）

**依赖：** 任务5；task permit续租与heartbeat生命周期绑定。

**文件：** 修改 `task/RedisConcurrencyLimiter.java`、`task/TaskWorkerPool.java`、`task/TaskRunner.java`、`service/TaskService.java`；新增 `task/RedisConcurrencyLimiterIntegrationTest.java`；默认配置在任务15seed统一补入。

- [ ] acquire用单段Lua完成过期清理、容量检查和添加，使用Redis TIME，避免多实例时钟偏差。

```lua
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then return 0 end
redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[3])
return 1
```

- [ ] renew用单段Lua确认token存在且未过期后更新score，不能重新添加已经失去的许可；release只删除自己token。key清理TTL要覆盖当前最大score，不能因后来短请求缩短已有长permit生命周期。
- [ ] user permit使用短TTL周期续租，不按1800/3600猜执行时长；token传入Task执行上下文，Task heartbeat成功后renew，任务终态/ownershipLost停止续租并释放。
- [ ] renew失败时停止领取新Item；permit已过期则丢弃Task所有权/走安全收尾，不能继续占用任务同时允许新Task取得同一容量。业务提交仍受任务4校验。
- [ ] 所有Task创建入口显式写 `max_execution_seconds`，统一使用 `task_max_execution_seconds`有效配置，默认3600；与WatchDog使用同一值，不再DB60分钟/permit32分钟不一致。
- [ ] AI permit TTL覆盖实际连接、读取、上传/处理时间；长请求需要续租或明确总deadline。acquire返回前、发AI前确认许可有效，不把超时请求记为成功。
- [ ] 热缩容保持已有合法permits，used>=max不发新permit；监控输出真实used而非 `min(used,max)`隐藏超占。

**验收：** 两个独立Redisson client共100并发请求/max=5，同时有效许可不超过5；续租跨越多个初始TTL不提前释放；已过期token不能renew复活；进程死亡后自动释放；容量10→2后不发新permit直到used<2；Redis故障时不突破容量；Task DB时限等于配置值。

### 任务 14：异步、有界内存的批量 EXPORT（R20）

**依赖：** 任务7、11。

**文件：** 修改 `controller/AdminExportController.java`、`service/TaskService.java` 的Task类型/admission、`frontend/src/api/admin.ts`、`frontend/src/pages/admin/PublishExport.tsx`、`frontend/src/api/tasks.ts`；新增 `pipeline/ExportTaskHandler.java`、`pipeline/ExportArtifactService.java`、`pipeline/ExportTaskHandlerTest.java`。产物元数据先用Task result保存，所需新增配置在任务15seed补齐。

**接口：** `POST /api/admin/export/batch`返回TaskVO而非ResponseEntity<byte[]>；Task result成功后保存artifactId、文件大小、校验摘要、有效期、逐作品报告。下载接口校验ADMIN和产物归属，流式读取文件/签名存储对象，不把绝对临时路径暴露给客户端。

- [ ] Export计划保存projectIds、每本PublicationSnapshot/版本和摘要；请求数量上限、去重、总估计大小、磁盘quota先校验，EXPORT遵循现有Task并发限制。
- [ ] 每本先流式生成到独立临时文件，成功后才加入外层ZIP；外层逐文件复制，内存只含有限缓冲与元数据。不能 `Map<String,byte[]>` 或总 `ByteArrayOutputStream`。
- [ ] 单本失败记录在summary并继续；外层ZIP/最终存储写失败则整个Task失败，不吞异常，不发布半包。success统计以真正加入最终包为准。
- [ ] 每本成功文件可作为durable检查点；恢复先校验文件摘要/快照，失效则仅重做该本；不将整个任务做成不可恢复的长HTTP响应。
- [ ] 应用多实例/重启时检查点存储必须共享或明确标记owner并重新生成；不能让另一实例引用不可访问的本地路径。
- [ ] 最终文件用临时命名生成，finish后原子发布artifact；finally关闭流，定时清理过期/失败产物，取消Task后不再返回下载URL。
- [ ] 允许部分作品失败时Task=PARTIAL、仍可下载summary和成功作品；全部失败时FAILED且summary可查询；界面显示逐本错误，不静默漏本。

**验收：** 合成20本×300MB流数据，峰值堆内存不随6GB产物增长（记录固定堆配置和峰值）；第5本读取失败不会损坏成功包，summary完整；外层磁盘满/存储失败不发布链接；stop与重启后检查点语义正确；越权下载拒绝，过期文件清理。

### 任务 15：Flyway Seed、CI和可持续观测（R21、R22、R29及R30观测）

**文件：** 新增 `server/src/main/resources/db/migration/V8_1_4__seed_defaults.sql`；修改 `server/sql/seed.sql`、`README.md`、`server/pom.xml`、`frontend/package.json`及lockfile；在 `docs/` 新增部署与迁移说明；批准目录例外后新增 `.github/workflows/ci.yml`；新增集成测试配置、前端必要的lint/test配置与关键状态交互测试；修改 `pipeline/StageItemCommitService.java`、`controller/AdminMonitorController.java` 和任务/限流观测入口。

- [ ] Seed移除固定 `USE aimanga_v2`，在实际Flyway datasource对应库执行；从当前seed提取非敏感默认项，按config_key/风格稳定标识幂等插入缺失值。
- [ ] 已有用户配置、模型地址、风格修改不被覆盖；不将第三方AI API地址硬当生产可用配置。新库API Key/OSS secret为空，首次配置状态明确。
- [ ] 包含前面新增的有效默认配置：Task执行/lease、permit renew、导出quota/有效期、心跳线程数等；每项注明单位和最小/最大范围。普通配置seed不创建默认弱密码管理员。
- [ ] 新空库仅Flyway即可得到全部schema/defaults；已有V8_0_3库升级保留原配置；重复启动无重复风格。V8_1_0—4按顺序落地，实施时若最新迁移改变，整体调整编号并记录。
- [ ] Maven引入Testcontainers MySQL/Redis测试支持，`mvn verify`必须实际运行集成测试；若使用`*IT`则配置Failsafe，不能新增IT类却默认不执行。
- [ ] frontend增加可运行的lint和Vitest/Testing Library测试，重点覆盖PAUSED占用槽位、resume/stop、项目暂停drain展示、EXPORT结果和错误；不添加只mock组件自身逻辑的测试。
- [ ] CI至少PR和main push运行：Java17 Maven verify；Node版本固定、npm ci、lint、test、build；集成测试具备Docker与隔离MySQL/Redis，无AI/OSS真实secret。
- [ ] CI路径过滤匹配main根目录 `server/**`、`frontend/**`，不是 `v2/**`；check名称稳定。GitHub branch protection需要仓库管理配置，工作流文件本身不会自动禁止未通过检查的合并；分别记录配置证据。
- [ ] stale拒绝计数改为可采集的实例级Counter并跨实例汇总；不直接引入当前配置明确排除的Actuator造成Shiro映射冲突。可先从已有ADMIN监控接口输出累计计数/实例标识，再接指标采集。
- [ ] 监控覆盖task heartbeat延迟/失败、ownershipLost、拒绝原因、暂停drain耗时、Worker期望/存活、队列补偿、permit used/max/renew失败、版本冲突、导出字节/失败/峰值资源。
- [ ] 指标标签限制stage/reason等低基数值；taskId/pageId/token写结构化日志不做指标标签，API Key/secret不进入日志。

**验收：** 空库与升级库Flyway测试通过；配置不覆盖；CI正常运行全部专项测试，故意引入前端类型错误/失败单测能让check失败；所有测试修复后通过；双实例拒绝事件可汇总；DB慢heartbeat产生告警且可定位，重启计数reset被采集系统正确处理。

## 6. 跨任务故障注入与最终 Gate

下列场景是收口要求，不由“已有70项测试通过”替代。

| 场景 | 操作 | 必须满足 |
|---|---|---|
| Task撤销而Item未撤销 | 阻塞AI→WatchDog撤销Task→释放旧AI | 旧Task不能写图、失败状态、记录或后继Task |
| 旧失败迟到 | A阻塞→B成功→A抛异常 | B的URL、SUCCESS/IDLE和error均不被A污染 |
| 合法STOPPING长请求 | stop后持续heartbeat，AI超过90秒 | 不领新Item，当前请求可提交，最终STOPPED |
| pause→立即resume | 旧Runner仍drain时resume | 意图版本生效，同Task继续，只需一次resume |
| 强制生成断点恢复 | 10页成功6页后pause/resume/restart | 成功6页不新增AI请求，统计累计不清零 |
| admission拒绝 | 已有active不同payload，发新请求 | 409且所有Item/计划/业务行无副作用 |
| 单页layout | 其他话存在stale/missing/FAILED | 其他范围Item完全不变 |
| SCRIPT重建 | 已SCRIPT_READY本话显式force | 真正生成本话，旧Page依赖清干净，其他话不执行 |
| 跨Stage竞争 | PAGE/CLEAN同时使用同revision | 仅一项成品CAS提交，冲突有明确原因 |
| 发布版本不一致 | 改脚本/对白后不重生成/同步 | Gate拒绝旧图/旧层，不能丢正文 |
| 导出快照竞争 | 打包中编辑/重新生成 | 一包只含一份一致快照，扩展名与字节格式对应 |
| Redis连续故障 | poll/marker/renew/requeue同时失败 | Worker未永久耗尽；限流不突破；恢复后DB任务可补偿 |
| 双实例限流 | 两实例抢许可、热缩容、杀一实例 | 原子容量正确、合法permit续租、死permit过期 |
| MySQL/迁移 | 新库、V8_0_3升级、JSON reset | Flyway有效，配置不覆盖，SQL真实通过 |
| 批量导出资源 | 6GB合成产物、单本失败、磁盘满 | 堆内存有界，无半包成功链接，summary准确 |

### 最终通过条件

- [ ] 任务1—15中所有批准项均有验证记录；未批准/延期项明确列出，不宣称Phase8全部完成。
- [ ] `mvn -B verify`、前端lint/test/build及MySQL/Redis集成测试通过。
- [ ] 上表关键ownership/pause/plan/image/publication故障注入全部通过。
- [ ] 不调用付费AI的双实例演练通过；实际供应商测试如另行进行，明确记录成本与幂等边界。
- [ ] 数据迁移在空库及备份恢复后的升级库验证；不修改历史migration。
- [ ] main目录结构符合审核决策；若批准CI例外，仅额外增加 `.github/`，不重新引入 `v2/`。
- [ ] 用户未授权的本地文件不在提交中，最终diff和测试证据可审查。

## 7. 上线与旧任务过渡

1. 在测试环境完成全部迁移与故障注入，再对生产数据备份验证恢复。
2. 部署新增计划/ownership字段前停止新领取，等待旧Worker drain或撤销lease；不让旧代码与新代码长期并行写新计划。
3. 为旧PENDING/PAUSED Task生成兼容计划；旧正在执行请求的处理策略必须明确为drain或撤销，不能默认认为它们有新所有权字段。
4. 校验旧图/旧文本版本缺失的页，显示待验证/待同步；不批量伪造版本相等。
5. 再恢复领取，先运行有限项目，观察ownership拒绝、重复计划、pause时长、permit续租和发布Gate问题。
6. 应用回退前确认旧代码不会绕过新增CAS/所有权；数据库采用向前修复，新增schema不要盲目回滚或删除持久化计划。

## 8. 审核与执行记录

### 审核勾选

- [x] 批次A：任务1—3。
- [x] 批次B：任务4—6；接受STOPPING/pause的drain语义。
- [x] 批次C：任务7—10；接受计划表和image_revision。
- [x] 批次D：任务11—13；接受发布版本Gate及正文fallback兼容改动。
- [x] 批次E：任务14—15；接受异步EXPORT和新seed迁移。
- [x] 批准新增根目录 `.github/workflows/ci.yml` 例外。

### 回填格式

| 任务 | 审核结果 | 实施提交 | 专项测试与结果 | 集成/故障注入证据 | 剩余限制 |
|---|---|---|---|---|---|
| 1—3 | 已批准并实施 | `e75d0a6`、当前收口提交 | TaskVO契约、严格空Scope、合法JSON reset；后端单测通过 | Scope Runner与Mapper SQL回归已覆盖 | MySQL实际SQL由CI Testcontainers继续验证 |
| 4—6 | 已批准并实施 | `e75d0a6`、`afc91ab`、`e9a15f3`、当前收口提交 | Task/Item双fencing、STOPPING续租、pause意图与快速resume测试通过 | stale Task/Attempt、旧失败迟到和心跳异常注入通过 | 供应商侧已发请求的收费无法由本地fencing撤销 |
| 7—10 | 已批准并实施 | 当前收口提交 | 固定计划、无副作用admission、精确重试、脚本重建清理与页面版本CAS已实现 | 旧任务安全迁移、Redis入队失败、页面计划取消回归通过 | 旧任务范围不明确时转人工失败，不做危险推断 |
| 11—13 | 已批准并实施 | 当前收口提交 | 发布版本Gate、正文fallback、真实MIME、Worker恢复、Lua限流和permit续租已实现 | 两客户端原子限流及升级库测试已加入Testcontainers | 本机无Docker，容器测试本地跳过，由CI执行 |
| 14—15 | 已批准并实施 | 当前收口提交 | 异步EXPORT、固定快照、流式临时文件、受控下载、过期清理、seed、前端Vitest和CI已实现 | 后端96项单测全通过；前端6项测试、lint、build通过 | 6GB压力基准需在具备磁盘配额的预发布环境执行；main分支保护需仓库管理员启用 |

### 2026-09-20 验证记录

- 后端：`mvn -B verify`，96项单元测试通过，0失败、0错误；Failsafe已发现`InfrastructureIT`。本机未安装Docker，1个容器测试类按`disabledWithoutDocker`跳过。
- 前端：`npm run lint && npm test -- --run && npm run build`，3个测试文件、6项测试通过，TypeScript与Vite生产构建通过。
- 集成测试：空库Flyway、V8.0.3升级保留运营配置、MySQL schema、Redis PING、两个Redis客户端原子限流及过期许可不可续租均已写入CI测试。
- 代码结构：实现文件只位于当前项目根目录的`docs/`、`frontend/`、`server/`、`.github/`、`.gitignore`和`README.md`结构内；提交前继续使用精确路径核对。
- 上线外部项：GitHub main分支保护需将`backend-verify`、`frontend-checks`设为必需检查；6GB合成导出需在预发布资源环境记录峰值堆内存与磁盘故障结果。
