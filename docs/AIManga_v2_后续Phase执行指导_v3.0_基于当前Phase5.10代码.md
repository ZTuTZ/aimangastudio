# AIMangaStudio v2 后续 Phase 执行指导 v3.0
## 基于当前 Phase 5.10 代码基线

> 本文档用于替代旧的 Phase 6/7 执行计划。  
> 本地 Agent 后续开发以当前代码为事实基线，并按本文档顺序执行。  
> 不回退 Phase 5.5~5.10 已完成架构，不重新引入“脚本完成后自动生成所有素材图”的旧流程。

---

# 0. 当前代码基线确认

当前项目已经完成并实际存在以下能力：

1. 长剧本拆话：`SPLIT`
   - `SourceTextIndexer`
   - 滚动小包拆话
   - Java 按 offset 重建原文
   - 全文覆盖校验

2. 文本资产提取：`ASSET`
   - 角色 / 场景 / 道具 / 服装
   - 按话分包
   - 全局资产合并

3. 脚本生成：`SCRIPT`
   - 每话 Stage Item
   - Source Spine 页级连续覆盖
   - 全局资产设定注入
   - 并发执行

4. 任务可靠性：
   - MySQL Task 事实源
   - Redis Queue
   - claim token
   - heartbeat
   - watchdog
   - pending 补偿
   - 服务重启恢复

5. Pipeline：
   - `PipelineStage`
   - `PipelineStageItem`
   - 暂停 / 继续
   - Stage Item 级断点

6. 并发执行引擎：
   - `ImageStageRunner`
   - `ImageWorkerPool`
   - Stage Item 原子领取
   - Item 独立失败重试
   - 并发数配置

7. 素材工作台：
   - 角色设定表：`SHEET`
   - 场景 / 道具 / 服装参考图：`ASSET_REF`
   - 用户在资产库勾选后批量生成
   - `feature_auto_sheet=0` 为当前产品行为

## 当前正式产品流程

```text
上传剧本
  ↓
SPLIT 拆话
  ↓
ASSET 文本资产提取
  ↓
SCRIPT 分话漫画脚本
  ↓
Project = 待出图
  ↓
【自动准备流程结束】

用户进入资产库
  ↓
按需勾选角色 / 场景 / 道具 / 服装
  ↓
SHEET / ASSET_REF 并发生成素材图
  ↓
用户确认素材

用户进入“生成成品”
  ↓
Phase 6 正式出图
```

注意：

- `ASSET` 文本资产提取仍属于自动准备流程。
- `SHEET / ASSET_REF` 图片素材生成不再自动串入主流水线。
- Phase 6 不允许静默自动补生成缺失素材图。
- 用户是否生成场景 / 道具 / 服装参考图属于用户选择。
- 角色参考图属于一致性最高优先级，Phase 6 需要做明确预检。

---

# Phase 5.11 —— 当前基线收口与并发安全修复

> 在正式进入 Phase 6 前先完成。  
> 本 Phase 不增加新业务能力，只修复当前 Stage Item / 手动素材工作台中会影响 Phase 6 的边界问题。

---

## T5.11.1 修复手动素材“重生成” Item 重置逻辑

当前：

`PipelineStageService.resetItemsByBusiness(...)`

仅重置：

```text
RUNNING
FAILED
```

但用户点击“重新生成”时，该素材通常已经是：

```text
SUCCESS
```

如果 SUCCESS 不重置为 PENDING，则 Stage Runner 不会再次领取。

### 修改

新增明确方法：

```java
forceResetItemsByBusiness(...)
```

允许：

```text
SUCCESS
FAILED
RUNNING
PENDING
    ↓
PENDING
```

同时清理：

```text
retry_count = 0
result_ref = null
error_message = ''
```

`SHEET / ASSET_REF` 用户手动勾选“生成/重新生成”必须使用该方法。

普通失败续跑仍使用现有 `resetFailedItems`，不得混用。

### 验收

已有角色设定表：

```text
角色A = SUCCESS
```

点击“重生成”。

必须真实产生一次新的生图请求并替换：

```text
sheet_image_url
```

---

## T5.11.2 修复 removeOrphanItems 删除主键错误风险

当前 `removeOrphanItems()` 取的是：

```text
business_id
```

随后调用：

```java
deleteBatchIds(...)
```

但 `deleteBatchIds` 按 `pipeline_stage_item.id` 删除。

### 修改

必须收集真正的：

```text
PipelineStageItem.id
```

再删除。

禁止使用业务 ID 作为 Stage Item 主键删除。

### 验收

删除一个资产后重新同步 SHEET / REFERENCE：

- 只删除该资产对应的 Stage Item；
- 不误删其他 Item；
- 不留下孤儿 Item。

---

## T5.11.3 禁止同一 project + stage 出现多个并行 Runner

当前手动素材生成接口可以连续点击。

风险：

```text
SHEET Task A 正在执行
SHEET Task B 又启动
```

同时 `ImageStageRunner.run()` 开头会：

```java
resetRunningItems(projectId, stageType)
```

第二个 Runner 可能把第一个 Runner 正在执行的 Item 从 RUNNING 重置成 PENDING，造成重复生图。

### 必须修改

实现“一个项目的同一 Stage 同时只允许一个活跃 Runner”。

推荐：

```text
projectId + stageType
```

作为 Stage 执行互斥键。

可使用：

```text
Redisson RLock
aimanga:v2:stage-run:{projectId}:{stageType}
```

但不要依赖锁作为唯一事实源。

同时任务创建层必须做去重：

```text
同 project + SHEET
同 project + ASSET_REF
同一时刻只能有一个 PENDING/RUNNING Task
```

### 用户再次勾选素材时

不要再创建第二个并行 Runner。

应：

1. 创建/重置新的 Stage Items；
2. 若已有活跃 Stage Task：
   - 复用当前 Task；
   - 当前 Runner 后续自动领取新增 PENDING Items；
3. 若没有活跃 Task：
   - 创建一个 Task。

建议新增：

```java
MaterialGenerationService
```

负责：

```text
requestCharacterSheets(projectId, assetIds)
requestAssetReferences(projectId, assetIds)
```

Controller 不再直接拼 Task。

---

## T5.11.4 不允许普通 Runner 无条件回收所有 RUNNING Item

`resetRunningItems()` 只应该用于：

```text
确定上一个执行者已经死亡
```

不能作为每次正常运行的通用初始化逻辑。

### 推荐方案

第一阶段至少满足：

- Stage Runner 有唯一执行锁；
- 同 stage 不存在第二个活跃 task；
- 只有 Recovery / watchdog 确认旧执行者死亡后，才重置 RUNNING。

如果保留 Handler 启动时 reset：

必须先成功拿到 project+stage 唯一执行锁，并确认不存在其他有效 Runner。

---

## T5.11.5 修复 REFERENCE 暂停后无法项目级 resume

当前 `firstIncompleteStage()` 顺序主要针对：

```text
SPLIT
ASSET
SCRIPT
SHEET
```

`REFERENCE` 属于手动素材阶段。

但 `pauseProject()` 会把所有 RUNNING Stage 都暂停。

因此可能出现：

```text
REFERENCE = PAUSED
```

而项目 resume 后没有任务重新启动它。

### 修改

项目级 resume：

1. 恢复所有 PAUSED Stage；
2. 根据实际暂停 Stage 类型分别 `ensureTask`：
   - SPLIT
   - ASSET
   - SCRIPT
   - SHEET
   - REFERENCE
   - 后续 LAYOUT
   - IMAGE

不能仅调用 `firstIncompleteStage()` 启动一个阶段。

主准备流水线仍按顺序恢复；
手动素材 Stage 独立恢复。

---

## T5.11.6 将 SCRIPT 并发与“生图并发”解耦

当前 `ScriptTaskHandler` 同样使用：

```text
ImageStageRunner
ImageWorkerPool
image_generation_concurrency
```

这会导致：

```text
SCRIPT 文本 AI 请求
```

占用：

```text
生图 Worker Pool
```

当 `image_generation_concurrency=20` 时，也可能一次向 SCRIPT 提交大量文本工作。

### 重构目标

将当前：

```text
ImageStageRunner
```

抽象成：

```text
ConcurrentStageRunner
```

Runner 只负责：

- Item claim
- retry
- pause
- resume
- progress
- drain

Executor 独立注入。

建议：

```text
ScriptWorkerPool
ImageWorkerPool
```

配置：

```text
script_item_concurrency
image_generation_concurrency
```

最终 AI 层仍受：

```text
ai_text_concurrency
ai_image_concurrency
ai_merge_concurrency
```

Redis 信号量做最后保护。

### 不允许

为了改名重写整套可靠性代码。

尽量把现有 Runner 抽象复用。

---

## T5.11.7 更新 schema / seed / docs 到当前真实行为

当前迁移已经把：

```text
feature_auto_sheet=0
```

但基础 `seed.sql` 仍可能存在旧默认：

```text
feature_auto_sheet=1
```

### 必须统一

新数据库初始化时默认：

```text
feature_auto_split = 1
feature_auto_asset = 1
feature_auto_sheet = 0
```

并更新：

- `00-README.md`
- `01-需求与页面规格.md`
- `02-技术架构设计.md`
- `03-开发任务计划.md`
- `schema.sql`
- `seed.sql`

当前正式准备流程必须写成：

```text
SPLIT → ASSET → SCRIPT → READY
```

素材图：

```text
SHEET / ASSET_REF = 用户按需手动触发
```

---

## Phase 5.11 Gate

全部满足后才能进入 Phase 6：

- 成功素材可以真实重生成；
- 连续快速点两次生成，不会出现重复生图；
- 同 stage 不存在两个 Runner 抢 Item；
- 删除资产不会误删别的 Stage Item；
- SHEET / REFERENCE 暂停后能继续；
- SCRIPT 不再占用 image worker pool；
- fresh install 的默认行为与当前产品决策一致。

---

# Phase 6.1 —— 页级素材绑定 + 出图预检

这是新版 Phase 6 最重要的基础。

当前 Page 只有：

```text
narration
dialogue
visual
scene_description
```

并没有明确记录：

```text
本页到底使用哪些 asset
```

如果 Phase 6 每次临时从 visual 文本里猜素材，将导致：

- 人物识别不稳定；
- 别名错配；
- 同名资产错配；
- 无法提前判断缺哪些素材图；
- 用户重新编辑资产后无法知道影响哪些页。

所以正式出图前必须建立：

> Page → Asset 的结构化绑定。

---

## T6.1.1 新增 page_asset_ref

推荐新增表：

```sql
CREATE TABLE page_asset_ref (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  project_id BIGINT UNSIGNED NOT NULL,
  page_id BIGINT UNSIGNED NOT NULL,
  asset_id BIGINT UNSIGNED NOT NULL,
  required_flag TINYINT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL DEFAULT 'AUTO',
  sort_order INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  UNIQUE KEY uk_page_asset (page_id, asset_id),
  KEY idx_par_project_page (project_id, page_id),
  KEY idx_par_asset (asset_id),

  CONSTRAINT fk_par_page
    FOREIGN KEY (page_id) REFERENCES page(id) ON DELETE CASCADE,

  CONSTRAINT fk_par_asset
    FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE
);
```

`source`：

```text
AI
MATCH
MANUAL
```

---

## T6.1.2 新增 PageAssetBindingService

职责：

```text
Page
  ↓
对白 speaker
visual
scene_description
  ↓
资产 name / aliases
  ↓
page_asset_ref
```

匹配优先级：

### 角色

1. `dialogue.speaker`
2. visual 中出现角色名
3. aliases 命中

角色默认：

```text
required_flag=1
```

### 场景 / 道具 / 服装

visual / scene_description 命中：

```text
required_flag=0
```

因为用户可能选择不为所有场景/道具生成素材图。

---

## T6.1.3 更新 SCRIPT 契约

未来新生成的 SCRIPT 不应该完全靠字符串反推素材。

修改 `AssetContextService`：

向模型提供资产 ID：

```text
[角色#123] 林凡 ...
[场景#201] 教室 ...
[道具#301] 黑色手机 ...
```

修改 `StoryScript.PageItem`：

增加：

```json
"assetIds":[123,201,301]
```

AI 返回后：

1. 校验 ID 必须属于当前 project；
2. 过滤非法 ID；
3. Java name/alias matcher 做补充；
4. 写 `page_asset_ref`。

### 兼容当前已经生成的旧 Page

不要要求用户重新跑 SCRIPT。

提供：

```java
PageAssetBindingService.rebuildForProject(projectId)
```

一次性根据已有：

```text
dialogue / visual / scene_description
```

生成绑定。

---

## T6.1.4 增加出图素材 Preflight

新增：

```java
GenerationPreflightService
```

接口建议：

```http
GET /api/projects/{id}/generation-preflight
GET /api/projects/{id}/generation-preflight?chapterId=123
```

返回：

```json
{
  "ready": false,
  "pageCount": 100,
  "requiredAssets": 8,
  "readyAssets": 6,
  "missingRequiredAssets": [
    {"id":12,"name":"林凡","assetType":1}
  ],
  "optionalMissingAssets": [
    {"id":31,"name":"学校天台","assetType":2}
  ],
  "warnings":[]
}
```

---

## T6.1.5 素材 Gate 规则

默认策略：

```text
character_required
```

### 角色

页面用到的角色必须至少存在一种可用视觉参考：

```text
sheet_image_url
    ↓ 优先

reference_url
    ↓ fallback
```

两者都为空：

```text
required missing
```

默认阻止 BATCH。

### 场景 / 道具 / 服装

有 reference_url：

使用。

没有：

允许继续，仅作为 warning，使用文字设定。

### 不允许

BATCH 遇到缺角色素材后：

```text
自动偷偷生成角色设定表
```

这违背当前“素材由用户自行选择生成”的产品决策。

---

## T6.1.6 前端预检

“生成成品”页面启动任务前先显示：

```text
素材准备情况
角色 6/8
场景 4/12
道具 2/7
服装 3/5
```

如果有缺失必需角色：

显示：

```text
缺少 2 个角色参考图
[前往资产库]
```

如果只是场景/道具缺图：

提示 warning，但允许继续。

---

# Phase 6.2 —— ReferenceResolver + LAYOUT 并发生成

---

## T6.2.1 新增 PageReferenceResolver

输入：

```text
pageId
```

输出按优先级排序的参考图：

1. 本页角色 `sheet_image_url`
2. 角色 `reference_url`
3. 场景 `reference_url`
4. 服装 `reference_url`
5. 关键道具 `reference_url`
6. style_preset.ref_images

最终 PAGE 阶段还要额外加入：

```text
layout_image_url
```

### 配置

新增：

```text
page_reference_max_images
```

默认建议：

```text
8
```

不能把本项目几十个素材全部塞给每一页。

---

## T6.2.2 PagePromptCompiler

禁止 LAYOUT / IMAGE Handler 各自拼 prompt。

新增：

```java
PagePromptCompiler
```

负责统一生成：

```text
LayoutPrompt
FinalPagePrompt
```

输入：

- Project 风格
- Page visual
- narration
- dialogue
- page asset refs
- selected material refs
- color mode
- aspect ratio

---

## T6.2.3 LayoutGenerationService

新增：

```java
LayoutGenerationService
```

职责：

```text
Page
+ Prompt
+ ReferenceResolver
↓
AI image
↓
OSS
↓
page.layout_image_url
```

### Layout 的目标

它只解决：

- 页内格子布局；
- 人物站位；
- 景别；
- 动作关系；
- 画面阅读顺序。

不要追求：

- 最终精细脸部；
- 最终文字准确性；
- 最终材质。

---

## T6.2.4 LAYOUT Stage Items

整部生成：

```text
LAYOUT Stage
```

每页：

```text
business_type=PAGE
business_id=page.id
```

全部使用现有并发 Stage Runner。

禁止：

```text
1 Page = 1 Task
```

必须保持：

```text
1 BATCH Task
  ↓
1 LAYOUT Stage
  ↓
N Page Items
```

---

## T6.2.5 LAYOUT 幂等

如果：

```text
layout_image_url 非空
```

且：

```text
forceLayout=false
```

直接 success。

手动重生成布局：

只 reset 指定 page 的 LAYOUT Item。

---

# Phase 6.3 —— IMAGE 成品页并发生成 + BATCH

---

## T6.3.1 新增 PageGenerationService

输入：

```text
page script
layout_image_url
PageReferenceResolver refs
style
colorMode
aspectRatio
```

生成：

```text
generated_image_url
```

必须顺序：

```text
AI 返回
 ↓
OSS 保存
 ↓
写 page.generated_image_url
 ↓
追加 generate_records
 ↓
Item SUCCESS
```

绝对不允许：

```text
先 Item SUCCESS
再保存业务结果
```

---

## T6.3.2 final reference 顺序

最终页参考图建议：

```text
layout_image
角色设定表
角色参考图
场景参考图
服装参考图
道具参考图
风格参考图
```

布局图表示：

```text
构图约束
```

角色图表示：

```text
身份约束
```

两者职责不要混淆。

---

## T6.3.3 不依赖上一页才能生成

禁止：

```text
Page 2 必须等 Page 1 完成
Page 3 必须等 Page 2 完成
```

否则整部漫画无法并发。

跨页一致性主要依赖：

- Asset Registry
- 角色设定表
- Page Asset Ref
- Style Preset

如果前一页已经存在，可作为低优先级可选参考；
但不得成为依赖。

---

## T6.3.4 实现 BATCH TaskHandler

`TaskService` 已经允许：

```text
BATCH
```

但当前没有 BATCH Handler。

新增：

```java
BatchTaskHandler
```

一个用户点击：

```text
整部生成
```

只创建：

```text
1 个 BATCH Task
```

BATCH 内部：

```text
Preflight
 ↓
LAYOUT Stage Items
 ↓
并发生成 Layout
 ↓
IMAGE Stage Items
 ↓
并发生成 Final Pages
 ↓
汇总
```

---

## T6.3.5 BATCH payload

建议：

```json
{
  "scope":"PROJECT",
  "chapterId":null,
  "colorMode":"partial",
  "skipGenerated":true,
  "forceLayout":false,
  "forceImage":false
}
```

按话生成：

```json
{
  "scope":"CHAPTER",
  "chapterId":123,
  "colorMode":"partial",
  "skipGenerated":true
}
```

---

## T6.3.6 BATCH Gate

开始前检查：

1. Project 存在；
2. 目标 Chapter 全部 `STATUS_SCRIPT_READY` 或更高；
3. 目标 Chapter 有 Page；
4. Page 非空；
5. `GenerationPreflightService` 通过；
6. 没有同项目活跃 BATCH。

失败必须明确返回：

```text
缺角色素材
脚本未完成
没有页面
已有生图任务执行中
```

---

## T6.3.7 项目 / 话 / 页状态

BATCH 开始：

```text
Project = GENERATING
目标 Chapter = GENERATING
目标 Page = RUNNING/PENDING
```

单页成功：

```text
Page = SUCCESS
```

失败：

```text
Page = FAILED
fail_reason = xxx
```

话全部成功：

```text
Chapter = COMPLETE
```

有失败：

```text
Chapter = PARTIAL_FAILED
```

整部全部成功：

```text
Project = COMPLETE
```

有失败：

```text
Project = PARTIAL_FAILED
```

---

## T6.3.8 断点恢复

服务重启：

```text
LAYOUT SUCCESS → 跳过
IMAGE SUCCESS → 跳过
RUNNING stale → Recovery 回收
PENDING → 继续
FAILED → 根据重试策略
```

已经成功的页面绝不重新产生 AI 费用。

---

## T6.3.9 默认封面

当：

```text
scope=PROJECT
```

并且项目最终全部生成完成：

如果：

```text
project.cover_url 为空
```

按：

```text
chapter_no ASC
page_no ASC
```

选择第一张：

```text
generate_status=SUCCESS
generated_image_url 非空
```

作为默认封面。

人工封面绝不覆盖。

---

# Phase 6.4 —— “生成成品”工作台

当前前端仍是：

```text
ComingSoon
```

Phase 6.4 替换为正式工作区。

---

## T6.4.1 生成控制区

支持：

### 范围

```text
整部作品
指定话
```

后续可扩：

```text
指定页
```

### 色彩

```text
partial
monochrome
color
```

### 行为

```text
跳过已生成页面（默认开）
重新生成布局
重新生成成品
```

---

## T6.4.2 素材预检卡片

展示：

```text
角色素材：8 / 8
场景素材：4 / 11
道具素材：3 / 9
服装素材：2 / 4
```

角色缺失时：

```text
不能开始
```

提供：

```text
前往资产库并自动筛选缺失角色
```

---

## T6.4.3 实时 Stage 进度

不要只显示 Task：

```text
56%
```

应该显示：

```text
布局图      72 / 100
成品页      41 / 100
失败页       2
```

数据来自：

```text
PipelineStage
PipelineStageItem stats
```

---

## T6.4.4 页画廊

按：

```text
话
  ↓
页
```

展示：

- 未生成
- 生成中
- 成功
- 失败

成功时实时显示 OSS 图。

失败页：

```text
[重试]
```

---

## T6.4.5 暂停 / 继续

BATCH 必须支持现有 Pipeline：

```text
暂停
继续
```

暂停后：

- 不领取新 Item；
- 已经发送的 AI 请求允许完成并保存；
- 继续后从未完成 Item 接着跑。

---

# Phase 6.5 —— PageDetail + 单页重生成 + 脚本变更失效控制

---

## T6.5.1 PageDetail

当前 PageDetail 仍未形成正式生产能力。

实现：

左侧：

```text
脚本信息
旁白
对白
visual
```

中间：

```text
layout 图
```

右侧：

```text
成品图
```

支持：

```text
布局 / 成品 对比
```

---

## T6.5.2 单页 LAYOUT

实现：

```http
POST /api/pages/{id}/generate-layout
```

内部：

- reset 指定 LAYOUT Item；
- 使用同一个 `LayoutGenerationService`；
- 不复制 BATCH 的业务逻辑。

---

## T6.5.3 单页 PAGE

实现：

```http
POST /api/pages/{id}/generate
```

使用：

```text
PageGenerationService
```

不得重新实现另一套 Prompt / Reference 逻辑。

---

## T6.5.4 脚本版本控制

当前用户已经可以编辑：

```text
narration
dialogue
visual
scene_description
```

但未来如果图片已经生成，再改脚本，系统需要知道成品已过期。

推荐 page 增加：

```sql
script_version INT NOT NULL DEFAULT 1,
layout_script_version INT NOT NULL DEFAULT 0,
image_script_version INT NOT NULL DEFAULT 0
```

PageService 修改脚本文字时：

```text
script_version + 1
```

Layout 成功：

```text
layout_script_version = script_version
```

IMAGE 成功：

```text
image_script_version = script_version
```

前端判断：

```text
image_script_version < script_version
```

显示：

```text
脚本已修改，当前成品需要重新生成
```

不要直接删除旧图。

旧图保留进：

```text
generate_records
```

---

## T6.5.5 Page Asset Ref 同步

用户修改：

```text
dialogue
visual
scene_description
```

后：

调用：

```text
PageAssetBindingService.rebuildPage(pageId)
```

保持素材绑定与新脚本一致。

---

# Phase 6.6 —— 后处理

等基础 BATCH 稳定后再做。

顺序不要提前。

---

## T6.6.1 COLORIZE

使用：

```text
merge 通道
```

输入当前成品图。

结果：

- OSS；
- generate_records；
- generated_image_url 更新。

---

## T6.6.2 CLEAN

清晰化 / 修复。

同样使用统一：

```text
PostProcessService
```

---

## T6.6.3 REPAINT

支持：

```text
原图
遮罩图
repaintPrompt
```

不能新写独立任务模型。

继续：

```text
Task
Stage Item
Concurrent Runner
Generation Records
```

---

# Phase 6.7 —— 生成记录与质量回溯

当前 `page.generate_records` 是 JSON。

MVP 可以继续使用。

但如果需要真正长期生产：

建议后续独立表：

```text
generation_record
```

至少记录：

```text
project_id
chapter_id
page_id
task_id
kind
model
prompt
reference_urls
input_url
result_url
status
create_time
```

目的：

- 重绘回溯；
- 对比模型；
- 查成本；
- 排查某批错误；
- 恢复历史版本。

本阶段不要阻塞 Phase 6 MVP，可在 BATCH 稳定后实现。

---

# Phase 7 —— 管理、发布包和批量预置内容

---

# Phase 7.1 系统配置管理收尾

配置中心增加 / 校准：

```text
script_item_concurrency
image_generation_concurrency
image_queue_size
image_gen_max_retry

page_reference_max_images
page_generation_asset_gate

ai_text_concurrency
ai_image_concurrency
ai_merge_concurrency
```

保存生图并发后：

```text
ImageWorkerPool.refresh()
```

必须热更新。

---

# Phase 7.2 全局任务监控

管理员任务中心至少显示：

```text
Task
Project
Stage
Stage Item Success / Total
Worker 状态
heartbeat
retry_count
last_error
```

但不要把每一个 Stage Item 当成独立 Task 展示。

用户主要看：

```text
一个漫画任务
布局 80/100
图片 62/100
```

---

# Phase 7.3 PublicationValidator

正式导出前验证：

## Comic

- content_uid
- title
- cover_url
- category
- tags

## Chapter

- chapter_no 连续
- title
- 至少有一页

## Page

必须：

```text
generate_status = SUCCESS
generated_image_url 非空
```

页号连续。

### 注意

正式漫画发布包：

不要求所有：

```text
SHEET
ASSET_REF
```

都存在。

这些属于 AI 生产资产，不是阅读数据。

---

# Phase 7.4 comic-content-1.0 导出

继续沿用 Phase 3.5 约定。

输出：

```text
comic-package/
  manifest.json
  ...
```

Manifest 核心：

```text
Comic
Chapter
Page
```

只导出最终阅读所需字段。

不导：

```text
user_id
task
pipeline_stage
pipeline_stage_item
prompt
retry_count
```

---

# Phase 7.5 批量导出

为了上线前批量准备内容，必须支持：

```text
选中 N 部已完成漫画
  ↓
批量校验
  ↓
批量导出
```

返回：

```json
{
  "total":1000,
  "success":982,
  "failed":18,
  "failedItems":[...]
}
```

不能要求人工逐部下载。

---

# Phase 7.6 APP Importer 模拟测试

在正式 APP 开发前，用一套空表模拟未来系统：

```text
Comic Package
  ↓
Importer
  ↓
comic
comic_chapter
comic_page
```

验证：

完全不需要当前生产系统的：

```text
project.id
user_id
chapter.id
page.id
task.id
```

仍能还原：

```text
漫画
  ↓
话
  ↓
页
```

---

# Phase 7.7 端到端验收

至少准备：

### 测试 A：短篇

```text
2~3话
```

### 测试 B：中篇

```text
20话
```

### 测试 C：长篇

```text
50话+
```

### 测试 D：3 部作品同时 BATCH

验证：

- text / image 并发互不错误耦合；
- 不超并发上限；
- 暂停 / 继续；
- 服务重启；
- Redis 临时异常；
- 单页失败；
- 单素材失败；
- BATCH partial；
- 重试不重复已成功页。

---

# 推荐实际执行顺序

本地 Agent 后续严格按下面顺序：

```text
Phase 5.11
当前 Stage / 素材工作台并发安全收口
        ↓
Phase 6.1
Page → Asset 结构化绑定 + 素材预检
        ↓
Phase 6.2
PageReferenceResolver + LAYOUT
        ↓
Phase 6.3
PAGE Final + BATCH
        ↓
Phase 6.4
生成成品前端工作台
        ↓
Phase 6.5
单页重生成 + PageDetail + 脚本版本
        ↓
Phase 6.6
COLORIZE / CLEAN / REPAINT
        ↓
Phase 6.7
Generation Record（建议）
        ↓
Phase 7
配置 / 监控 / 发布包 / 批量导出 / Importer 验收
```

---

# Agent 执行原则

## 1. 每 Phase 单独提交

禁止一次把 Phase 6 全做完。

推荐 Git：

```text
phase-5.11-stabilize
phase-6.1-page-asset-binding
phase-6.2-layout
phase-6.3-final-page-batch
phase-6.4-generation-ui
phase-6.5-page-regeneration
phase-6.6-postprocess
phase-7-release
```

---

## 2. 新能力优先复用现有架构

必须复用：

```text
Task
PipelineStage
PipelineStageItem
ConcurrentStageRunner
AiService
OSS Storage
SSE
```

禁止重新写一套：

```text
CompletableFuture + 内存进度
```

作为正式业务状态。

---

## 3. 一页不是一个 Task

必须保持：

```text
1 个 BATCH Task
N 个 Stage Items
```

单页独立重生成才允许创建：

```text
PAGE / LAYOUT Task
```

---

## 4. 不恢复旧的全自动素材生成

自动准备流程固定：

```text
SPLIT
  ↓
ASSET 文本提取
  ↓
SCRIPT
  ↓
READY
```

素材图：

```text
用户选择
```

Phase 6 只做检查和使用。

---

## 5. 不为了“角色一致性”牺牲页级并发

不要设计：

```text
Page N 必须依赖 Page N-1
```

角色一致性由：

```text
Asset Registry
Page Asset Ref
Sheet Image
Reference Resolver
Style
```

保证。

---

# 最终目标架构

```text
                    上传剧本
                       │
                       ▼
                  SPLIT Stage
                       │
                       ▼
                  ASSET Stage
                文本资产提取
                       │
                       ▼
                  SCRIPT Stage
                  逐话并发生成
                       │
                       ▼
                 Project READY
                       │
          ┌────────────┴────────────┐
          │                         │
          ▼                         ▼
       资产库                   生成成品
          │                         │
 用户勾选需要生成的素材             ▼
          │                  Generation Preflight
     ┌────┴────┐                    │
     ▼         ▼                    ▼
   SHEET    ASSET_REF         Page Asset Binding
     │         │                    │
     └────┬────┘                    ▼
          │                    LAYOUT Stage
          │                    N Page Items
          │                         │
          │                         ▼
          │                     并发布局
          │                         │
          └──────────────► ReferenceResolver
                                    │
                                    ▼
                               IMAGE Stage
                               N Page Items
                                    │
                                    ▼
                               并发成品页
                                    │
                                    ▼
                                 OSS / DB
                                    │
                                    ▼
                                Page Gallery
                                    │
                                    ▼
                        重生成 / 后处理 / 发布
```

---

# 本轮不建议提前做的内容

在 BATCH 成品页稳定之前，不要优先做：

- 自动 QC 大模型审核；
- 自动重绘 Agent；
- 推荐系统；
- APP 评论/点赞；
- 付费体系；
- 复杂成本核算；
- 大规模 Redis Stream 重构；
- Kubernetes；
- 自建 GPU 集群。

先把：

```text
剧本 → 内容结构 → 素材 → 页 → 稳定并发生图 → 可恢复 → 可导出
```

整条生产链真正跑稳。

---

# 最终验收目标

达到以下状态后，当前“批量漫画生产系统”才算完成核心闭环：

1. 上传长剧本后自动得到稳定的 Chapter / Page 脚本；
2. 用户自行选择素材生成，不被系统强制全量烧图；
3. 出图前明确知道缺哪些关键角色素材；
4. 每页确定引用哪些人物 / 场景 / 道具 / 服装；
5. 整本漫画可以高并发生成；
6. 失败只影响对应页；
7. 暂停继续不重复成功工作；
8. 服务重启后从断点继续；
9. 单页可以独立重生成；
10. 最终漫画可校验、批量导出并导入未来 APP。
