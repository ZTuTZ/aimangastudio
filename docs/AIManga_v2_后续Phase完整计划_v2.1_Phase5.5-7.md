# AIMangaStudio v2 — 后续 Phase 完整开发计划 v2.1

> 替代当前 `03-开发任务计划.md` 中 **Phase 5 完成后的后续计划**。  
> 当前状态：Phase 1~5 与 Phase 3.5 已完成；先执行新增 Phase 5.5，再进入本文新版 Phase 6、Phase 7。  
> 详细 Phase 5.5 实现以《AIManga_v2_Phase5.5_长剧本拆话资产与脚本一致性重构执行文档.md》为准。

---

# 执行总顺序

```text
[已完成] Phase 1 工程骨架
[已完成] Phase 2 认证与账号
[已完成] Phase 3 基础数据 + AI/OSS
[已完成] Phase 3.5 漫画内容兼容层
[已完成] Phase 4 任务系统
[已完成] Phase 5 第一步流水线初版

[新增必做] Phase 5.5 长剧本与一致性重构
        ↓
[新版] Phase 6 第二步流水线：稳定出图
        ↓
[新版] Phase 7 管理/发布包/端到端收尾
```

---

# Phase 5.5 长剧本、资产与脚本一致性重构

## T5.5.1 原文 SourceUnit 索引 + SPLIT 小包规划

### 目标

彻底去除长文本 30000 字截断和“AI 回传完整 scriptText”的设计。

### 产出

- `SourceTextIndexer / SourceUnit`；
- `ChapterSplitPlanner`；
- SPLIT 按约 8000 字滚动窗口调用；
- AI 只返回 `endUnit/title/summary`；
- Java 根据 offset 切 `chapter.script_text`；
- 全文连续覆盖硬校验；
- 不再限制整本 2~8 话；
- 元数据改为拆话后独立小请求。

### 验收

- 3k / 30k / 50k / 100k 字真实剧本通过；
- 所有 chapter 拼接逐字符等于 source_text；
- AI 输出不包含完整 `scriptText`；
- 超过 30000 字的正文不丢失。

---

## T5.5.2 ASSET 按话分包 + Global Asset Merge

### 目标

保证整部长篇后半部资产不漏，并形成 SCRIPT/Phase6 共用的全书标准资产库。

### 产出

- `AssetPackBuilder`；
- 每包最多 5 话且约 12000 字；
- pack 内受控并发；
- `AssetMergeService`；
- name/aliases 基础归并；
- 保护已有人工 description/structured/reference_url/sheet_image_url。

### 验收

- 后半本首次出现角色能被提取；
- 多包同名不产生重复 DB 行；
- aliases 可合并；
- 人工参考图不被自动流程覆盖。

---

## T5.5.3 自动任务链重排与去重

### 新链路

```text
SPLIT → ASSET → SCRIPT×N → SHEET → READY
```

### 产出

- SPLIT 完成后先入队 ASSET；
- ASSET 完成后按话入队 SCRIPT；
- SCRIPT 全部 ready 后只入队一次 SHEET；
- `enqueueUnique` / Redisson 短锁防重复链任务；
- `feature_auto_asset/feature_auto_sheet` 语义同步调整。

### 验收

- 多话 SCRIPT 同时结束只出现 1 个自动 SHEET；
- 任务停止/重试不会制造重复子任务；
- `content_uid` 始终不变。

---

## T5.5.4 SCRIPT 注入 Global Asset Registry

### 产出

- `prompt_script` 增 `{assets}`；
- AssetContext 筛选当前话相关角色/场景/道具；
- 已有角色不得重新设计；
- speaker 校验使用全局角色 name + aliases + 本话新角色；
- SCRIPT 只允许补充漏抽新人物，不覆盖 canonical 设定。

### 验收

随机抽同一主角连续 5 话：基础发型/配饰/服装事实稳定；同一 alias 不生成两个人物卡。

---

## T5.5.5 SCRIPT Page Source Spine

### 产出

`StoryScript.PageItem` AI DTO 增：

```text
sourceStartUnit
sourceEndUnit
```

程序验证每话所有页的 Unit 连续覆盖全部 chapter.script_text。

### 验收

- 第一页从 U1 开始；
- 最后一页覆盖末 Unit；
- 无 gap / overlap / reverse；
- AI 返回非法范围时只重试该话；
- 最后一话过短时允许少于默认页数，不造空页。

---

## T5.5.6 回归与 Phase6 Gate

### Gate 条件

只有同时满足以下条件才允许进入 Phase 6：

```text
1. 长文 SPLIT 100% 覆盖原文；
2. ASSET 覆盖整部作品；
3. 所有 chapter SCRIPT_READY；
4. 页级 Source Spine 校验通过；
5. Global Asset Registry 已建立；
6. SHEET 已终态；
7. Project STATUS_READY。
```

---

# Phase 6 第二步流水线（新版：稳定出图）

## Phase 6 核心原则

Phase 6 不允许 AI 每页重新猜：

```text
角色长什么样
角色穿什么
本页有哪些角色
布局之后该参考谁
```

这些事实应从 Phase 5.5 的 `asset + page` 数据中确定后再调用图片模型。

同时：

> 跨页一致性可以利用前页图片作为**可选参考**，但绝不能让 PAGE N 必须等待 PAGE N-1 完成，否则 BATCH 无法页级并发。

---

## T6.1 出图公共服务层：ReferenceResolver + Layout/Page Generator

### 目标

先建立 LAYOUT、PAGE、BATCH 共用的确定性出图能力，避免三个 Handler 各写一套逻辑。

### 建议新增

```text
pipeline/image/PageReferenceResolver.java
pipeline/image/LayoutGenerationService.java
pipeline/image/PageGenerationService.java
pipeline/image/GenerationRecordService.java
```

### PageReferenceResolver

根据当前 `PageEntity`：

```text
dialogue speaker
visual
narration
scene_description
```

匹配本项目 `asset`：

```text
角色 name/aliases
场景 name/aliases
道具 name/aliases
服装 name/aliases
```

参考图优先级：

1. 当前页说话角色的 `sheet_image_url`；
2. 当前页说话角色的 `reference_url`（无 sheet 时）；
3. visual 明确出现的其他角色；
4. 相关场景 reference_url；
5. 相关关键道具 reference_url；
6. style preset ref_images；
7. 已存在的前一页成功成品图（可选连续性参考，最低优先级之一）。

新增配置建议：

```text
page_reference_max=8
page_use_prev_reference=1
```

超过模型参考图上限时按优先级截断，并写日志说明实际用了哪些资产。

### 验收

给一页包含两名对白角色的 page，resolver 必须优先选到这两名角色的设定图，不能随机取项目其它人物。

---

## T6.2 LAYOUT 单页布局生成

### 目标

生成当前页的漫画构图参考，而不是最终成品。

### 输入

```text
project aspect_ratio
style
page narration/dialogue/visual/scene_description
相关资产文字上下文
```

LAYOUT 阶段重点：

```text
分格数量
格子比例
人物站位
镜头景别
对白气泡/旁白框预留区域
阅读顺序
```

不得在布局图阶段追求最终角色面部细节；避免布局图错误身份反过来污染 PAGE。

### prompt_layout 要求

- 只服务漫画页面布局；
- 阅读顺序清楚；
- 避免所有页都是机械上下二分；
- 根据 page.visual 决定 1~多格；
- 不添加原 page 脚本没有的角色/剧情；
- 文字内容可不真实渲染，最终气泡文本以 page.dialogue/narration 为准。

### 数据写入

成功：

```text
page.layout_image_url = OSS URL
```

不改变 `generated_image_url`。

---

## T6.3 PAGE 单页最终成品生成

### 输入

```text
layout_image_url（优先参考）
PageReferenceResolver 选出的资产图
page script
project style / color_mode / aspect_ratio
可选 prev generated page
```

### 硬规则

1. 当前页说话角色必须和 asset canonical identity 一致；
2. 不得额外复制同一角色；
3. 不得引入 page script 不存在的关键人物；
4. 页面剧情只能来自当前 page；
5. 色彩模式服从 project/任务 payload；
6. 成品成功上传 OSS 后才写 `generated_image_url`；
7. 覆盖旧成品前先写 `generate_records` 历史记录。

### 失败

```text
page.generate_status=3
page.fail_reason=具体错误
```

### 成功

```text
page.generate_status=2
page.generated_image_url=OSS
page.color_mode=实际模式
fail_reason=''
```

---

## T6.4 BATCH 整部/按话一键生成

### 重要架构调整

**BATCH 不要“入队 PAGE 子任务后同步等待 PAGE task 完成”。**

原因：全局 worker 较小时会出现父 BATCH 占住 worker、子 PAGE 等待 worker 的潜在饥饿/死锁。

正确方式：

```text
BATCH TaskHandler
   ↓
内部 bounded executor（task_page_concurrency）
   ↓
直接调用 LayoutGenerationService
   ↓
直接调用 PageGenerationService
```

独立的 `PAGE` / `LAYOUT` TaskHandler 也调用同样公共 Service。

即：

```text
任务 Handler 负责调度
GenerationService 负责真实生成
```

### BATCH 功能

- projectId；
- 可选 chapterId；
- colorMode；
- skipGenerated；
- force（管理员/明确重跑才使用）。

### 执行前 Gate

范围内 chapter 必须：

```text
status >= SCRIPT_READY
pages 非空
每页至少有 narration/dialogue/visual 中一种内容
```

### 页级并发

使用已有：

```text
task_page_concurrency
```

实际 AI 请求仍受：

```text
ai_image_concurrency
ai_merge_concurrency
```

控制。

### 幂等

若：

```text
generate_status == 2
&& generated_image_url 非空
&& skipGenerated=true
```

直接计成功/跳过，不重新扣费。

### 断点续跑

失败页写入 `task.result.failedPages`；retry 时优先只跑失败/未完成页。

### 项目/话状态

开始：

```text
Project → GENERATING
Chapter → GENERATING
```

话全部页成功：

```text
Chapter → DONE
```

有失败：

```text
Chapter → PARTIAL
```

整部成功：

```text
Project → DONE
```

任意正式页失败：

```text
Project → PARTIAL
```

### 默认封面

整部完成后：

```text
若 project.cover_url 为空
→ chapter_no ASC / page_no ASC 的第一张成功 generated_image_url
→ 写 cover_url
```

人工已有封面绝不覆盖。

---

## T6.5 独立 PAGE/LAYOUT 重生成

### PAGE task

默认：

```text
重新生成 layout + final page
```

payload 可支持：

```json
{"reuseLayout":true}
```

此时保留现有 layout，仅重生成 final page。

### LAYOUT task

只更新 layout，不自动覆盖 final page；前端提示：

> 布局已变化，如需应用到成品请再执行“重新生成成品”。

避免用户改布局后系统静默烧一次额外 PAGE 请求。

---

## T6.6 页画廊 + 页详情 + 对比视图

延续原计划：

- PageGallery 按话分组；
- 状态角标；
- OSS 缩略图；
- 100+ 页懒加载；
- PageDetail；
- layout vs generated CompareViewer；
- 脚本编辑；
- 单页下载；
- 单页 LAYOUT/PAGE 重生成。

新增展示建议：页详情显示“本次引用资产”：

```text
林凡（角色设定表）
苏雨（角色设定表）
教室（场景参考）
```

这可以只通过生成前 resolver 日志/接口临时返回，首版不要求新增 DB 字段。

---

## T6.7 后处理：COLORIZE / CLEAN / REPAINT

保留原计划。

### 共通规则

覆盖 `generated_image_url` 前，将旧图追加到：

```text
page.generate_records
```

每条至少：

```json
{
  "url":"...",
  "colorMode":"partial",
  "kind":"PAGE|COLORIZE|CLEAN|REPAINT",
  "time":"..."
}
```

### REPAINT

- Mask 上传 OSS；
- 使用当前 generated_image_url + mask + prompt；
- 只改遮罩区域；
- 角色相关局部重绘仍应带对应角色 reference/sheet，避免脸变人。

### 验收

- 上色不改变人物身份；
- 清晰化不重新构图；
- 局部重绘选区外尽量稳定；
- 历史图可回看。

---

## Phase 6 总验收

至少使用：

```text
3 部作品并行
每部 ≥5 话
每话约10页
至少 2 个多人对白场景
```

验证：

1. BATCH 页级并行正常；
2. 不出现 BATCH/PAGE worker 等待死锁；
3. 同角色跨页/跨话基本身份一致；
4. 对白页优先使用对应角色设定图；
5. 失败页可 retry 且成功页不重复；
6. 服务重启后任务可恢复；
7. 人工封面不覆盖；
8. `generated_image_url` 才是正式成品页；
9. Project/Chapter/Page 状态一致。

---

# Phase 7 管理后台、发布包与收尾（新版）

## T7.1 系统配置 / Prompt / 风格预设管理

保留原 Phase 7.1，并新增 Phase 5.5/6 配置 UI：

```text
split_pack_max_chars
split_target_chars_per_page
split_min_chars_per_page
split_max_chars_per_page
asset_pack_max_chapters
asset_pack_max_chars
asset_pack_concurrency
script_asset_context_max
page_reference_max
page_use_prev_reference
```

分组展示：

```text
长剧本规划
资产提取
脚本生成
图像生成
并发
```

Prompt 管理补齐：

```text
prompt_split
prompt_metadata（建议新增）
prompt_asset
prompt_script
prompt_layout
prompt_page
prompt_colorize
prompt_clean
prompt_repaint
```

如果不新增 `prompt_metadata`，也可使用代码内置模板；但为了后期运营调参，建议配置化。

### 验收

配置保存后下一任务生效；密钥继续脱敏；普通 USER 不可访问。

---

## T7.2 全局任务监控

保留：

```text
/admin/tasks
/admin/overview
```

新增长剧本任务可观测信息：

SPLIT task.result 建议可记录：

```json
{
  "sourceChars":100000,
  "sourceUnits":1260,
  "splitPacks":15,
  "chapters":148,
  "metadataWarning":""
}
```

ASSET task.result 建议：

```json
{
  "packs":30,
  "rawAssets":260,
  "mergedAssets":83
}
```

这些仅用于生产排障，**不进入 comic-content-1.0 manifest**。

---

## T7.3 正式发布包校验器 PublicationValidator

在 Export 前新增：

```text
PublicationValidator
```

建议新增：

```text
service/export/PublicationValidator.java
service/export/ComicExportService.java
```

正式发布包默认必须满足：

### Comic

```text
content_uid 非空
content_uid 唯一
title 非空
description 可选但建议警告
cover_url 非空（可自动补）
category 合法
tags 为合法 JSON 数组
```

### Chapter

```text
至少 1 话
chapter_no ASC
chapter_no 不重复
每话至少 1 个正式页
```

### Page

```text
page_no ASC
page_no 不重复
generate_status=2
generated_image_url 非空
```

默认情况下任何缺图：

```text
拒绝正式 export
```

管理员：

```text
allowPartial=true
```

才允许调试导出，并：

```json
"complete": false
```

---

## T7.4 comic-content-1.0 导出

沿用 Phase 3.5 已确定协议。

ZIP：

```text
作品名/
├── manifest.json
├── 第1话/
│   ├── 第1页.png
│   └── ...
└── 第2话/
```

`manifest.json` 只使用未来 APP 必要内容：

```text
schemaVersion
contentUid
title
tagline
description
coverUrl
category
tags
seriesStatus
aspectRatio
colorMode
complete
chapters[]
  chapterNo
  title
  pages[]
    pageNo
    imageUrl
    filePath
```

明确禁止：

```text
user_id
task
生产 status
Prompt
fail_reason
generate_records
style_preset_id 作为外键
SourceUnit / source offset
AI pack 调试信息
```

由于当前与未来均确定使用阿里云 OSS：

```text
imageUrl / coverUrl 可直接保留当前 OSS 正式 URL
```

不增加 storageKey 迁移设计。

---

## T7.5 未来 APP 导入模拟测试

这是预置漫画数据上线前的重要新增验收。

不需要开发真正 APP，只写一个测试 Importer / 测试表或内存对象：

```text
读取 manifest.json
    ↓
创建 Comic
    ↓
创建 Chapter
    ↓
创建 Page
```

必须证明：**完全不读取 AIMangaStudio 当前数据库内部 ID，也能重建阅读结构。**

验收：

```text
project.id 不参与
project.user_id 不参与
chapter.id 不参与
page.id 不参与
```

只靠 `comic-content-1.0` 即可得到：

```text
漫画 → 话 → 页 → OSS 图片 URL
```

同一个 `contentUid` 二次导入测试：

- 可识别为同一作品；
- 测试 Importer 可做 upsert，而不是创建重复漫画。

---

## T7.6 批量导出（建议加入，服务于上线预置内容）

由于本项目明确要在正式 APP 上线前批量生产一批漫画，建议 Phase 7 增加管理员批量导出能力，而不是人工逐部点 ZIP。

建议接口：

```text
POST /api/admin/projects/export-batch
```

输入：

```json
{
  "projectIds":[1,2,3],
  "allowPartial":false
}
```

或按条件：

```text
status=DONE
createTime range
```

首版可以生成一个批次目录/多个 ZIP，不要求把数千部全部塞入一个巨大 ZIP。

推荐：

```text
batch-export-20260910/
  comic-<contentUid>.zip
  comic-<contentUid>.zip
  batch-result.json
```

`batch-result.json`：

```json
{
  "total":100,
  "success":98,
  "failed":2,
  "items":[
    {"contentUid":"...","status":"SUCCESS","file":"..."},
    {"contentUid":"...","status":"FAILED","error":"缺第7话第3页"}
  ]
}
```

这不是未来 APP 内容协议，只是生产交付清单。

---

## T7.7 端到端验收 + 部署文档

完整回归流程更新为：

```text
批量上传 3 部（其中至少1部长文本）
  ↓
SPLIT 小包拆话
  ↓
ASSET 分包合并
  ↓
SCRIPT + Page Source Spine
  ↓
SHEET
  ↓
人工抽查/编辑资产
  ↓
3 部同时 BATCH
  ↓
单页重生成
  ↓
REPAINT / CLEAN / COLORIZE
  ↓
PublicationValidator
  ↓
comic-content-1.0 export
  ↓
测试 Importer 重建阅读结构
```

### 非功能验收

- 任务进度 SSE ≤2s；
- 服务重启恢复；
- 3 部 BATCH 并行；
- 长 SPLIT 不再依赖单次超长请求；
- USER 无系统配置访问权；
- 前端 build；
- 后端 test；
- Nginx SSE 配置写入 `04-部署手册.md`。

---

# 后续统一开发约定

1. 每个 Task 完成后先自测再进入下一个 Task。
2. 实现与文档冲突：**先更新文档，再改代码**。
3. 每 Task 一个 git commit。
4. AI 负责“判断和创作”，程序负责“原文、顺序、身份、覆盖和状态”。
5. 禁止重新引入任何 30000 字整本截断。
6. 禁止让 SPLIT AI 回传整段 chapter 原文。
7. 禁止 SCRIPT 自行覆盖全局人物 canonical 设定。
8. 禁止 BATCH 父任务通过等待子 PAGE task 的方式占住 worker。
9. 禁止将生产调试字段加入 `comic-content-1.0`。
10. Phase 6 开始前必须完整通过 Phase 5.5 Gate。

---

# 推荐 Git Commit 顺序

```text
v2(T5.5.1): add source indexer and rolling chapter split
v2(T5.5.2): chunk asset extraction and global merge
v2(T5.5.3): reorder pipeline and dedupe chained tasks
v2(T5.5.4): inject canonical assets into scripts
v2(T5.5.5): add page source spine validation
v2(T5.5.6): long-script regression gate

v2(T6.1): add page reference resolver and generation services
v2(T6.2): implement layout generation
v2(T6.3): implement final page generation
v2(T6.4): implement batch generation and resume
v2(T6.5): implement standalone page/layout regenerate
v2(T6.6): page gallery and detail compare
v2(T6.7): colorize clean repaint

v2(T7.1): admin pipeline config and prompts
v2(T7.2): improve global task observability
v2(T7.3): add publication validator
v2(T7.4): implement comic-content export
v2(T7.5): add manifest import simulation test
v2(T7.6): add admin batch export
v2(T7.7): e2e regression and deployment docs
```

