# AIMangaStudio v2 — Phase 5.5 长剧本拆话、资产与脚本一致性重构执行文档

> 文档用途：**直接交给本地开发 Agent 执行**。  
> 执行时点：当前 Phase 5 已完成，**必须先完成本 Phase 5.5，再进入新版 Phase 6**。  
> 基线代码：用户当前 `v2(1).zip`。  
> 参考项目：用户提供的“归档”AI 漫剧项目，仅借鉴其“小角 15s v2 / packByApi / 分包资产提取”的**确定性规划思想**，不得照搬视频时长、镜头、转场等视频规则。  
> 数据库：沿用 Phase 3.5 已完成结构，**本 Phase 默认不新增数据库字段、不修改 comic-content-1.0 协议**。  
> OSS：继续使用当前阿里云 OSS URL 方案，不修改。

---

## 0. 本 Phase 要解决的问题

当前漫画项目 Phase 5 已能完成 SPLIT / SCRIPT / ASSET / SHEET，但长文本下存在结构性风险：

1. `SplitTaskHandler` 当前最多截取约 30000 字送给 AI，超过部分不会参与拆话。
2. SPLIT 要求 AI 把每一话完整 `scriptText` 再输出一次，导致“长输入 + 长 JSON 输出”，容易超时、截断和 JSON 失败。
3. `DEFAULT_SPLIT` 将整本限定为 2~8 话，不能适配数万字甚至更长文本。
4. `AssetTaskHandler` 同样只取前约 30000 字，后半部首次出现的人物、场景、道具、服装可能完全漏掉。
5. 当前流水线顺序为 `SPLIT → SCRIPT×N → ASSET → SHEET`；SCRIPT 会各自重新设计角色形象，多个话并行时容易产生同一角色不同外貌/服装设定。
6. 当前 SCRIPT 只要求“按原文顺序”，但没有程序侧的“原文覆盖脊”验证，AI 仍可能出现漏剧情、跨段、跳序。
7. 后续 Phase 6 一旦开始生图，上述错误会固化成大量错误图片，返工成本远高于现在修正。

本 Phase 的核心原则：

> **程序负责原文、边界、顺序、覆盖完整性等确定性事实；AI 只负责语义判断、标题摘要、漫画页视觉设计。**

这与漫剧项目“小角 v2”的核心思想一致，但漫画的规划单位改为：

```text
长剧本
  ↓
漫画话 Chapter
  ↓
漫画页 Page
  ↓
Phase 6 图像
```

**不得引入 15 秒、30 秒、镜头秒数、视频帧、转场时长等规则。**

---

# 1. 新的准备流水线

Phase 5.5 完成后，默认自动流水线调整为：

```text
上传 source_text
    ↓
SPLIT
  ├─ 本地 SourceUnit 编号/offset
  ├─ 小包 AI 判断自然分话边界
  ├─ Java 按 offset 截取原始 script_text
  ├─ 全文连续覆盖校验
  └─ 小请求生成漫画 metadata
    ↓
ASSET
  ├─ 按 chapter 分包
  ├─ 多包提取角色/场景/道具/服装
  └─ 全局合并/别名归一
    ↓
SCRIPT × N
  ├─ 注入全局资产标准设定
  ├─ 每话按 SourceUnit 规划漫画页
  ├─ 页级 source range 连续覆盖校验
  └─ 写 page
    ↓
SHEET
  └─ 给最终角色资产生成设定表
    ↓
Project STATUS_READY
    ↓
Phase 6 出图
```

默认链路由原来的：

```text
SPLIT → SCRIPT×N → ASSET → SHEET
```

调整为：

```text
SPLIT → ASSET → SCRIPT×N → SHEET
```

原因：先形成整部作品的统一 Asset Registry，再让每话 SCRIPT 使用同一套人物事实，避免每话自行设计角色。

---

# 2. T5.5.1 — SourceTextIndexer：原文确定性索引层

## 2.1 目标

新增一个**纯 Java、本地执行、不调用 AI**的原文索引器。所有长文本拆话与 SCRIPT 页覆盖校验都基于它。

建议新增：

```text
server/src/main/java/com/aimanga/v2/pipeline/text/SourceTextIndexer.java
server/src/main/java/com/aimanga/v2/pipeline/text/SourceUnit.java
```

`SourceUnit` 至少包含：

```java
record SourceUnit(
    int index,          // 1-based
    int startOffset,    // sourceText inclusive
    int endOffset,      // sourceText exclusive
    String text
) {}
```

## 2.2 硬规则

1. **不得修改 `project.source_text` 原文。**
2. SourceUnit 只是原文视图，不做改写、去标点、翻译、清洗后覆盖。
3. unit 优先在以下自然位置断开：
   - 空行 / 段落边界；
   - 换行；
   - 中文/英文句末标点；
   - 超长单段时再按可接受长度切句。
4. 建议单个 unit 的可视正文控制在约 80~500 字之间；这是索引粒度，不是漫画话长度。
5. 所有 unit 的 offset 必须满足：

```text
first.startOffset == 0
unit[i].endOffset == unit[i+1].startOffset
last.endOffset == sourceText.length()
```

6. 对 AI 展示时使用：

```text
U0001|原文内容
U0002|原文内容
...
```

7. AI 返回的是 Unit 编号，Java 最终用 offset 从原始 `source_text` 截取 chapter，禁止让 AI 返回完整 chapter 原文。

## 2.3 单测

至少覆盖：

- 中文小说，多段落；
- Windows `\r\n`；
- 连续空行；
- 只有一大段无换行；
- 中英文混排；
- emoji / Unicode；
- 重建所有 unit 后必须与原始字符串逐字符一致。

---

# 3. T5.5.2 — 重构 SPLIT：滚动小包，只让 AI 返回边界

## 3.1 需要修改的现有类

重点修改：

```text
pipeline/SplitTaskHandler.java
pipeline/ChapterSplitResult.java
pipeline/PipelinePrompts.java
```

建议新增：

```text
pipeline/split/ChapterSplitPlanner.java
pipeline/split/ChapterBoundaryResult.java
pipeline/split/ProjectMetadataResult.java
pipeline/split/ChapterPlan.java
pipeline/split/ChapterRebuildService.java
```

### 必须删除

从 SPLIT 逻辑删除：

```java
private static final int MAX_SOURCE_CHARS = 30000;
```

以及任何：

```java
text.substring(0, 30000)
```

逻辑。

同时不再要求 AI 返回 `scriptText`。

---

## 3.2 新 SPLIT AI 契约

建议 `ChapterBoundaryResult`：

```java
public record ChapterBoundaryResult(List<Boundary> chapters) {
    public record Boundary(
        Integer endUnit,
        String title,
        String summary
    ) {}
}
```

AI 输出示例：

```json
{
  "chapters": [
    {
      "endUnit": 18,
      "title": "重生归来",
      "summary": "主角醒来后确认自己回到灾难发生前"
    },
    {
      "endUnit": 34,
      "title": "末日倒计时",
      "summary": "主角发现灾难征兆并开始准备物资"
    }
  ]
}
```

AI **绝对不返回**：

```text
scriptText
完整原文
改写后的正文
```

---

## 3.3 漫画话长度策略

不再把“每话 300~600 字、整本 2~8 话”写死。

使用 `storyboard_page_count` 推导目标话长度。

新增 system_config：

```text
split_pack_max_chars=8000
split_target_chars_per_page=60
split_min_chars_per_page=35
split_max_chars_per_page=90
```

若：

```text
storyboard_page_count = 10
```

则建议 AI：

```text
目标约 600 字/话
软范围约 350~900 字/话
```

这里是**软范围**：剧情自然断点优先于机械字数。

允许：

- 重要反转处 300~400 字提前结束；
- 一个完整冲突 700~900 字后结束；
- 最后一话因剩余正文较短而低于最小范围。

不得为了凑字数：

- 切断连续对白；
- 切断同一动作；
- 把一个连续场面强行拆两话；
- 跨段拼接；
- 重排剧情。

---

## 3.4 滚动分包算法

`ChapterSplitPlanner` 从未消费的第一个 Unit 开始，每次向后取不超过：

```text
split_pack_max_chars 默认 8000
```

的 Unit。

非最后一包：

1. AI 在当前窗口内返回若干个**已自然完成**的漫画话边界。
2. 窗口尾部如果只是下一话的开头/半段剧情，AI **不得为了用完窗口强行切话**。
3. Java 只确认到“最后一个 AI 返回的有效 endUnit”。
4. 未确认的尾部 Unit 留到下一次调用继续参与规划。
5. 下一包从 `lastConfirmedEndUnit + 1` 继续。

最后一包：

- 最后一话必须覆盖到最后一个 SourceUnit。

建议给 AI 只读上下文：

```text
上一话 title + summary
上一话最后 1~2 个 Unit（只作为语义上下文，不允许作为新 cut）
```

避免滚动包之间失去剧情连续性。

### 防卡死

若非最后包 AI 连续两次都没有返回任何有效边界：

1. 不得无限重试；
2. 在“目标话长度附近”的自然 SourceUnit 边界做 fallback；
3. fallback 优先：段落结束 > 句末 > unit 结束；
4. 记录 WARN；
5. 继续处理后文。

因为 8000 字窗口远大于默认单话约 600 字，正常情况下几乎必然存在可切点。

---

## 3.5 SPLIT Prompt 新默认模板

替换当前 `DEFAULT_SPLIT`。核心要求如下（Agent 可在此基础上优化措辞，但契约不可变）：

```text
你是漫画分话规划器。你只负责选择“漫画话”的自然结束边界和生成话标题/摘要。
系统已经把故事原文按顺序编号为 U0001、U0002...。

【最重要】
- 禁止改写、删减、复述、翻译正文。
- 禁止输出完整原文。
- 只返回 endUnit、title、summary。
- endUnit 必须来自输入 Unit 编号，严格递增。
- 剧情必须按原文顺序。

【漫画分话目标】
目标每话约 {target_chars} 字；建议范围 {min_chars}~{max_chars} 字，但自然剧情断点优先。
优先在以下位置结束一话：
1. 一个事件阶段完成；
2. 场景自然切换；
3. 冲突升级；
4. 重要信息揭露；
5. 人物做出关键决定；
6. 悬念/反转/危险出现，适合作为追读钩子。

禁止：
- 切断同一轮连续对白；
- 切断连续动作；
- 切在一句话中间；
- 重排；
- 跨段拼接；
- 为了凑字数强制切窗口尾部。

当前是否最后一包：{is_last_pack}
若不是最后一包，窗口尾部剧情尚未自然完成时可不返回该尾部边界。
若是最后一包，最后一个 endUnit 必须是 U{last_unit}。

只返回合法 JSON：
{"chapters":[{"endUnit":18,"title":"...","summary":"..."}]}

【上一话上下文】
{previous_context}

【当前待规划原文】
{text}
```

需要给 PromptService 增加占位：

```text
{target_chars}
{min_chars}
{max_chars}
{is_last_pack}
{last_unit}
{previous_context}
{text}
```

---

## 3.6 Java 侧硬校验

每个 AI pack 结果至少校验：

```text
chapters 不为空（非最后包 fallback 机制除外）
endUnit 在当前可切 Unit 范围内
endUnit 严格递增
不能回到 previous confirmed unit
title 非空（空则 Java 使用“第N话”）
summary 可空但建议保留
```

整部规划完成后必须验证：

```text
Chapter1.startOffset == 0
Chapter[i].endOffset == Chapter[i+1].startOffset
最后一话.endOffset == sourceText.length()
```

然后逐话：

```java
chapter.scriptText = sourceText.substring(startOffset, endOffset);
```

### 最重要的验收断言

所有 chapter 的 `script_text` 按顺序拼接后，必须与 `project.source_text` **逐字符完全一致**。

如果不一致：

```text
SPLIT 任务失败
不得写半成品 chapter
不得继续 ASSET/SCRIPT
```

---

## 3.7 数据库写入必须最后一次性提交

长文规划时不要每完成一个 AI 包就立刻破坏旧 chapter。

推荐流程：

```text
先在内存得到完整 ChapterPlan
    ↓
validateCoverage()
    ↓
@Transactional
删除旧 chapter（仍保留原“已有 page 禁止重拆”规则）
插入全部新 chapter
    ↓
提交事务
```

推荐使用一个单独 `ChapterRebuildService` 的 `@Transactional` 公共方法，避免同类 private/self-invocation 导致事务失效。

---

# 4. T5.5.3 — Metadata 从 SPLIT 大请求中剥离

当前 SPLIT 同时生成：

```text
tagline / description / category / tags / seriesStatus
```

保留功能，但改成**拆话完成后的独立小请求**。

输入建议仅使用：

```text
作品标题
各话 chapterNo + title + summary
正文首部少量摘录（可选）
正文尾部少量摘录（可选）
```

不要再把整本 source_text 发一次。

建议新增 `ProjectMetadataResult`：

```java
record ProjectMetadataResult(
    String tagline,
    String description,
    String category,
    List<String> tags,
    Integer seriesStatus
) {}
```

沿用 Phase 3.5 规则：

- 默认只填空字段；
- `payload.refreshMetadata=true` 才覆盖已有人工值；
- `content_uid` 永不触碰；
- category 继续走 `PipelineUtils.normalizeCategory()`；
- tags 继续验证 JSON 数组。

如果所有元数据已有人工值且 `refreshMetadata=false`，可以直接跳过这次 AI metadata 请求。

Metadata 请求失败策略：

- **不能导致已经正确拆好的 chapter 丢失。**
- 建议 SPLIT 任务记 WARN，但拆话主结果可以成功；缺失元数据后续人工补或单独重刷。
- 如果现有任务状态体系不支持 warning，可将 metadata 作为非关键步骤：失败写 task.result 警告，不 stepFail。

---

# 5. T5.5.4 — ASSET 改为按话分包提取 + 全局合并

## 5.1 删除整本截断

修改：

```text
pipeline/AssetTaskHandler.java
pipeline/AssetExtractResult.java（如有必要）
pipeline/PipelinePrompts.java DEFAULT_ASSET
```

删除：

```java
private static final int MAX_SOURCE_CHARS = 30000;
```

及所有前 30000 字截断。

---

## 5.2 分包策略

新增配置：

```text
asset_pack_max_chapters=5
asset_pack_max_chars=12000
asset_pack_concurrency=2
```

先读取已经拆好的 `chapter.script_text`，按 `chapter_no ASC` 分组。

一个资产包同时满足：

```text
最多 5 话
且最多约 12000 字
```

哪个条件先达到就结束当前包。

不得截断某一话正文；若单独一话超过 12000 字，则该话独占一包并完整发送，必要时再使用 SourceUnit 子分包，但正常 SPLIT 设计下不应发生。

---

## 5.3 多包执行

每包独立调用 `prompt_asset`，由 AI 返回：

```text
角色
场景
道具
服装
```

单包结果不要立即写 DB。

推荐：

```text
Pack1 Result
Pack2 Result
Pack3 Result
    ↓
AssetMergeService
    ↓
统一 canonical 结果
    ↓
一次 upsert 数据库
```

好处：避免两个并发包同时判断“数据库不存在同名资产”后重复创建。

AI text 通道本身仍受现有 Redisson `ai_text_concurrency` 限制；`asset_pack_concurrency` 只是单个 ASSET task 内部的小包并发上限。

---

## 5.4 新增 AssetMergeService

建议：

```text
pipeline/asset/AssetMergeService.java
pipeline/asset/AssetPackBuilder.java
```

最少实现以下确定性合并：

### 同 type + 同 name

合并为一条：

```text
aliases = union 去重
```

角色 `structured` 各字段：

```text
优先已有 DB 人工值
其次选择非空值
冲突时保留第一次 canonical 值，不自动覆盖
```

`description`：

- 已有 DB 非空描述不覆盖；
- AI 多包之间可选择信息更完整的一条，或做去重拼接但必须限制长度。

### name 命中另一资产 aliases

例如：

```text
资产A name=林凡 aliases=[小凡,林少]
新资产 name=林少
```

应合并到 `林凡`，不得创建第二张人物卡。

### alias 互相包含

优先选择**第一次在 chapter_no 较小处出现的正式名**作为 canonical name。

### 不允许跨 asset_type 合并

```text
角色“白龙”
道具“白龙剑”
```

不能因字符串包含关系合并。

### 模糊冲突

Phase 5.5 首版不要求实现复杂 AI 同人判定；无法确定的名称保留两条并写 WARN，避免错误合并。后续可增强。

---

## 5.5 保护人工资产

如果数据库已有资产：

```text
reference_url
sheet_image_url
description
structured
aliases
```

自动 ASSET 不得破坏人工/已生成内容。

规则：

1. 不删除已有资产；
2. `reference_url` / `sheet_image_url` 永不由 ASSET 覆盖；
3. 非空 description / structured 默认不覆盖；
4. aliases 允许 union；
5. 新发现资产新增。

如果以后需要“全量重建资产”，必须使用显式 payload，例如：

```json
{"rebuild":true}
```

且前端二次确认；本 Phase 默认不做自动删除重建。

---

# 6. T5.5.5 — 重排任务链：SPLIT → ASSET → SCRIPT → SHEET

这是本 Phase 的必要改动。

## 6.1 SplitTaskHandler 完成后

当前行为：直接给每话入队 SCRIPT。

修改为：

```text
if feature_auto_asset == 1:
    enqueueUnique(project, ASSET)
else:
    enqueue SCRIPT × N
```

---

## 6.2 AssetTaskHandler 完成后

当前行为：ASSET 结束后直接入队 SHEET。

修改为：

```text
ASSET 完成
  ↓
为所有 chapter 入队 SCRIPT（每话一个）
```

**ASSET 此时不要入队 SHEET。**

原因：SCRIPT 仍允许补充极少数 ASSET 漏掉的新角色；最终 SHEET 应在所有 SCRIPT 完成后再跑一次，保证新增角色也能生成设定表。

---

## 6.3 ScriptTaskHandler 完成后

删除：

```text
全部 SCRIPT ready → enqueue ASSET
```

改为：

```text
全部 chapter status >= SCRIPT_READY
    ↓
if feature_auto_sheet == 1:
    enqueueUnique(project, SHEET)
else:
    project.status = READY
```

---

## 6.4 SheetTaskHandler

保留“完成后推进 Project STATUS_READY”的职责。

即最终：

```text
SHEET 结束（允许个别角色失败形成 PARTIAL，但 task 已终态）
  ↓
Project STATUS_READY
```

如果 `feature_auto_sheet=0`，则由最后一个完成的 SCRIPT 推进 STATUS_READY。

---

## 6.5 必须增加链式任务去重

当前多个 SCRIPT 并发结束时存在同时判断 allReady 并重复入队 SHEET 的竞态风险。

建议新增：

```java
PipelineContext.enqueueUnique(...)
```

或在 `TaskService` 增加：

```java
createSystemTaskIfAbsent(projectId, chapterId, taskType, payload)
```

至少对以下自动链任务去重：

```text
ASSET：projectId + type + PENDING/RUNNING 唯一
SHEET：projectId + type + PENDING/RUNNING 唯一
SCRIPT：projectId + chapterId + type + PENDING/RUNNING 唯一
```

不要求新增 DB unique key；可以在 Service 中查询活动任务后创建，但必须考虑并发。更稳妥可用 Redisson 短锁：

```text
aimanga:v2:enqueue:{projectId}:{chapterId-or-0}:{type}
```

锁内查询 + 创建。

---

# 7. T5.5.6 — SCRIPT 使用全局资产，不再每话重新设计人物

## 7.1 当前问题

当前 `DEFAULT_STORYBOARD` 有：

```text
【角色设定】提取故事中的主要角色,设定全书统一的视觉形象
```

但 SCRIPT 本身是“每话一个 AI 请求”，所以“全书统一”实际上无法保证。

修改为：

> 全书人物/场景/道具资产已经由 ASSET 阶段确定。SCRIPT 必须服从这些标准设定，不得重新设计已有角色。

---

## 7.2 新增 Asset Context

在 `PipelineContext` 或独立 `AssetContextService` 中增加：

```java
String buildScriptAssetContext(Project project, Chapter chapter)
```

上下文至少包含相关角色：

```text
角色名
aliases
身份 role
年龄
发型
配饰
上衣
下装
description
```

建议同时包含本话正文明确提到的相关场景/道具/服装。

不要无脑把上百个资产全部塞给每一话。

推荐筛选优先级：

1. name 在 chapter.script_text 中直接出现；
2. alias 在本话出现；
3. 角色资产优先于场景/道具；
4. 若筛选后角色为空，追加前若干个主角色作为兜底；
5. 配置上限，例如 `script_asset_context_max=30`。

新增 system_config：

```text
script_asset_context_max=30
```

---

## 7.3 prompt_script 新占位

新增：

```text
{assets}
```

Prompt 中明确：

```text
【全书标准资产设定｜最高优先】
{assets}

- 已存在于标准资产中的人物，姓名、发型、配饰、服装等不得重新设计。
- 本话视觉描述引用人物时必须重复/遵循标准设定。
- 若本话确实出现标准资产中完全没有的新人物，可以在 characters 中报告该人物，但禁止修改已有角色。
```

---

## 7.4 speaker 校验范围必须扩大

当前 `normalize()` 仅使用 `script.characters()` 判断 speaker 是否是真实角色。

修改为 speaker 合法集合：

```text
全局 Asset Registry 的角色 name + aliases
+
本次 script.characters() 新发现角色 name/role
```

否则当 AI 服从新 Prompt、不重复输出已有 characters 时，会错误地把已有角色台词并入旁白。

---

## 7.5 upsertCharacters 调整

保留 SCRIPT 对“漏抽新人物”的兜底能力，但：

1. 先按 canonical name + aliases 查找现有角色；
2. 命中 alias 时补到 canonical，不创建第二卡；
3. 已有 description/structured 非空绝不覆盖；
4. 只补缺失字段；
5. SCRIPT 不承担重新设计角色职责。

---

# 8. T5.5.7 — 漫画页 Source Spine：SCRIPT 页级原文连续覆盖

这一项建议**必须在 Phase 6 前完成**，否则拆话正确但 AI 分页仍可能漏剧情。

## 8.1 思想

漫剧“小角 v2”是：

```text
程序保证对白/场序脊
AI 只设计视觉
```

漫画对应为：

```text
程序保证每话原文按页连续覆盖
AI 负责该页的漫画视觉/旁白/对白组织
```

---

## 8.2 StoryScript 扩展（只改 AI DTO，不要求改 DB）

给 `StoryScript.PageItem` 增加：

```java
Integer sourceStartUnit,
Integer sourceEndUnit
```

例如：

```json
{
  "page": 1,
  "sourceStartUnit": 1,
  "sourceEndUnit": 4,
  "narration": "...",
  "dialogue": [],
  "visual": "..."
}
```

这里 Unit 是对**本话 chapter.script_text 重新编号**的 U0001...。

无需把 sourceStartUnit/sourceEndUnit 存进未来 APP 数据，也不要求修改 `page` 表；它们只是 AI 契约与程序校验锚点。

---

## 8.3 SCRIPT prompt 改造

传给 AI 的本话正文改为编号文本：

```text
U0001|...
U0002|...
```

同时要求：

```text
- pages 必须按 page 递增；
- 每页 sourceStartUnit/sourceEndUnit 表示本页覆盖的连续原文区间；
- 第1页必须从 U0001 开始；
- 下一页 start = 上一页 end + 1；
- 最后一页必须覆盖最后一个 Unit；
- 不得重复 Unit；
- 不得跳 Unit；
- 每页 narration/dialogue/visual 只基于自己覆盖范围和必要的视觉连续上下文；
- 禁止提前使用后面页面的剧情信息。
```

---

## 8.4 页数

`storyboard_page_count` 继续作为**目标页数**。

对于正常 SPLIT 产生的 350~900 字/话，默认 10 页可以继续使用。

为了避免最后一话很短却强行造空页，建议：

```text
if chapter 字数 < page_count * split_min_chars_per_page:
    actualPageCount = max(1, ceil(字数 / split_target_chars_per_page))
else:
    actualPageCount = storyboard_page_count
```

并限定 `actualPageCount <= storyboard_page_count`。

示例：

```text
storyboard_page_count=10
最后一话仅 180 字
actualPageCount≈3
```

禁止通过复制剧情凑 10 页。

---

## 8.5 Java 侧页覆盖硬校验

`ScriptTaskHandler.validate()` 增加：

```text
pages 非空
page 序号连续
sourceStart/sourceEnd 均合法
第1页 start=1
page[i+1].start=page[i].end+1
最后一页 end=本话 unitCount
无 gap
无 overlap
无倒序
```

校验失败：只重试本话 SCRIPT，不影响其它话。

AI 连续失败后任务失败，禁止写入一套覆盖不完整的 page。

---

# 9. T5.5.8 — 新配置项与 seed

在 `server/sql/seed.sql` 增加（`ON DUPLICATE KEY` 保持当前行为）：

```text
split_pack_max_chars=8000
split_target_chars_per_page=60
split_min_chars_per_page=35
split_max_chars_per_page=90
asset_pack_max_chapters=5
asset_pack_max_chars=12000
asset_pack_concurrency=2
script_asset_context_max=30
```

配置组建议：

```text
split_* → pipeline
asset_pack_* → pipeline
script_asset_context_max → pipeline
```

管理端 Phase 7 再补 UI；本 Phase 只要求配置可通过 `ConfigService` 读取并有代码默认值。

禁止把 8000/12000/5 等再次写成不可配置常量作为唯一来源。

---

# 10. T5.5.9 — 超时与重试策略

本次**不通过单纯提高 timeout 解决长文本**。

现有 `ai_text_timeout` 可以继续保留。

规则：

### SPLIT

- 每个小包 AI 最多重试 2 次；
- 只重试当前小包；
- 同一确定性契约错误连续出现可快速失败/fallback；
- 不重新请求已经成功的前置包。

### ASSET

- 每个 asset pack 独立重试；
- 单包失败最终应导致 ASSET task FAILED，默认不允许静默漏掉后半本资产；
- 重试时其它已经成功的 pack 结果可保留在本次任务内存中。

### SCRIPT

- 每话独立 task，继续使用当前任务级重试；
- Source Spine 验证失败属于该话 SCRIPT 失败；
- 不重跑整本。

---

# 11. T5.5.10 — Phase 5.5 验收矩阵

必须使用真实长剧本验收，不能只用 3000 字测试。

## Case A：3000 字短故事

预期：

- 自动拆多话；
- 所有 chapter 拼接 == source_text；
- ASSET 正常；
- SCRIPT 页覆盖完整；
- SHEET 正常；
- Project READY。

## Case B：30000~50000 字

预期：

- SPLIT 出现多个小包 AI 调用；
- 无 substring 截断；
- 后半本文本确实产生 chapter；
- 后半本首次登场角色能进入 asset；
- 无 AI 请求需要回传几万字 `scriptText`。

## Case C：100000 字左右长篇

预期：

- SPLIT 不因单次请求超时而整体失败；
- 每次 AI 输入受 `split_pack_max_chars` 控制；
- 最终 source_text 覆盖 100%；
- chapter 数不受 8 话限制；
- ASSET 多包完整执行；
- 后 70% 正文出现的关键人物至少可被提取。

## Case D：最后剩余文本很短

预期：

- 最后一话允许低于正常软范围；
- SCRIPT 自动减少页数；
- 不生成空白/重复剧情页面。

## Case E：AI 返回非法 cut

例如：

```text
越界
倒序
重复
返回不存在 Unit
```

预期：

- Java 拒绝；
- 只重试当前 pack；
- 不污染 DB。

## Case F：多包人物别名

不同包分别返回：

```text
林凡
小凡
林少
```

且 aliases 能建立关系。

预期：

- 尽可能归并为单 canonical 角色；
- 不覆盖已有人工参考图/设定表。

## Case G：SCRIPT 多话一致性

检查同一主角至少 5 话的 `page.visual`：

- 发型；
- 配饰；
- 基础穿搭；
- 身份。

不得每话重新随机设定。

---

# 12. 本 Phase 明确不做

1. 不修改 Phase 3.5 `content_uid` / `comic-content-1.0`。
2. 不改阿里云 OSS URL 存储方案。
3. 不开发 APP 数据库。
4. 不加入视频的 15 秒 / 30 秒规则。
5. 不在拆话阶段生成图片。
6. 不把 LAYOUT/PAGE 提前做到本 Phase。
7. 不为 SourceUnit 建数据库表。
8. 不要求 page 增加 source offset 数据库字段。
9. 不把所有生产字段导入未来漫画 APP。

---

# 13. 本 Phase 完成后必须同步修改的文档

执行代码前/后同步修改：

```text
v2/docs/00-README.md
v2/docs/01-需求与页面规格.md
v2/docs/02-技术架构设计.md
v2/docs/03-开发任务计划.md
```

关键文字统一改成：

```text
第一步准备流水线：
SPLIT（长文本滚动分包、程序原文切片）
→ ASSET（按话分包、全局合并）
→ SCRIPT×N（使用全局资产 + 页级原文覆盖锚点）
→ SHEET
```

旧的：

```text
SPLIT → SCRIPT×N → ASSET → SHEET
```

必须全部替换，防止后续 Agent 按旧文档把代码改回去。

---

# 14. Agent 提交要求

建议 commit：

```text
v2(T5.5): refactor long-script split asset and story spine
```

提交说明必须包含：

1. 改动文件列表；
2. SPLIT 实际长文本 pack 日志示例；
3. 一次 `source_text == concat(chapter.script_text)` 的断言结果；
4. ASSET pack 数量与合并前/后资产数；
5. SCRIPT 页 Source Spine 校验结果；
6. `mvn test`；
7. 前端 `npm run build`（若无前端改动也要确认未破坏）。

**Phase 5.5 验收通过后，才允许进入新版 Phase 6。**
