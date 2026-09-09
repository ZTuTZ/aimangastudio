# AIMangaStudio v2 — Phase 3.5 漫画内容兼容改造执行文档

> 目标读者：本地开发 Agent  
> 当前项目：AIMangaStudio v2 批量 AI 漫画生产系统  
> 当前进度：Phase 1～3 已完成，准备进入 Phase 4 任务系统  
> 本文目标：在不把批量生产系统改造成“未来漫画 APP 数据库”的前提下，补齐未来线上 APP 导入所需要的最小漫画内容字段，并约束 Phase 5～7 的数据沉淀与导出格式。

---

## 1. 改造背景与最终目标

AIMangaStudio v2 当前是“漫画生产系统”，未来的 APP/微信小程序是“漫画阅读/发布/运营系统”。两者数据库**不要求整表一致**。

未来 APP 只需要从本系统拿到一部漫画的核心内容：

```text
漫画作品
├── 标题
├── 短简介
├── 正式简介
├── 封面
├── 主分类
├── 标签
├── 连载状态
├── 话
│   ├── 话序号
│   ├── 话标题
│   └── 页
│       ├── 页序号
│       └── 最终成品图 OSS URL
```

因此本次改造遵循以下原则：

1. **不追求未来 APP 与本系统表结构一致。**
2. **不把 APP 业务字段提前塞进本系统。**
3. `user_id`、任务状态、AI Prompt、失败原因、生成记录等仍只服务本生产系统，未来导入时可完全忽略。
4. 阿里云 OSS 现在和未来都继续使用，因此 `generated_image_url`、`cover_url` 可以直接作为正式内容 URL，不做 storageKey 迁移设计。
5. 保留当前 `project → chapter → page` 主结构，不重命名、不拆表。
6. 为作品增加一个稳定的跨系统标识 `content_uid`；话和页暂不增加 UID，使用 `content_uid + chapter_no + page_no` 即可稳定定位。
7. Phase 7 导出必须从“纯图片 ZIP”升级为“图片 ZIP + manifest.json”，`manifest.json` 是未来 APP 导入的内容协议。

---

## 2. 当前数据库判断

### 2.1 当前 `project`

现有关键字段：

```text
id
user_id
title
source_text
aspect_ratio
color_mode
style_preset_id
status
tagline
create_time
update_time
```

问题：

- 已有 `title`、`tagline`，但缺少正式作品简介。
- 缺少封面字段。
- 缺少作品主分类和标签。
- `status` 是“AI 生产状态”，不能当未来 APP 的“连载/完结状态”。
- `id` 是当前 MySQL 自增 ID，不应该成为未来 APP 的跨系统内容标识。

### 2.2 当前 `chapter`

现有字段已经满足未来漫画内容导入的核心需求：

```text
project_id
chapter_no
title
```

以下字段继续作为生产数据保留，但未来 APP 可不导入：

```text
script_text
status
page_count
create_time
update_time
```

**本次不要求修改 chapter 表。**

### 2.3 当前 `page`

现有字段已经满足未来漫画阅读内容的核心需求：

```text
chapter_id
page_no
generated_image_url
```

以下字段继续作为 AI 生产数据保留，未来 APP 可不导入：

```text
narration
dialogue
visual
scene_description
layout_image_url
color_mode
generate_status
fail_reason
generate_records
```

**本次不要求修改 page 表。**

### 2.4 当前 `asset`

`asset` 继续服务角色、场景、道具、服装的 AI 生产一致性。

未来首批漫画如果只是用于 APP 阅读，资产数据不是必需导入项。

**本次不修改 asset 表，不把 asset 纳入必选导出协议。**

### 2.5 `user / task / system_config / style_preset`

本次全部保持原结构。

尤其注意：

- `project.user_id` = 当前批量生产系统操作账号，不等于未来 APP 作者 ID。
- `task.*` = 生产任务事实，未来 APP 不导入。
- `style_preset_id` = 当前生产系统内部风格表 ID，未来 APP 不应直接依赖此 ID。

---

## 3. 本次数据库最终改造范围

只对 `project` 增加以下字段：

| 字段 | 类型 | 是否必填 | 用途 |
|---|---|---:|---|
| `content_uid` | `CHAR(36)` | 是 | 跨系统稳定作品 ID，创建后永久不变 |
| `description` | `TEXT` | 否 | 漫画正式简介 |
| `cover_url` | `VARCHAR(512)` | 否 | 漫画封面 OSS URL |
| `category` | `VARCHAR(64)` | 否 | 主分类，如悬疑/古风/科幻 |
| `tags` | `JSON` | 否 | 标签数组，如 `["重生","系统","异能"]` |
| `series_status` | `TINYINT` | 是 | 内容状态：`1=连载中 2=已完结`，默认 2 |

保留现有：

- `tagline`：作为卡片短简介/一句话卖点。
- `status`：仍只代表生产状态，不修改语义。
- `aspect_ratio`、`color_mode`：生产属性，同时允许写入导出 manifest 作为附加元数据。

### 3.1 不新增以下字段

本次明确**不要**新增：

```text
author_id
app_user_id
publish_status
review_status
view_count
like_count
favorite_count
comment_count
price
is_free
recommend_score
published_at
```

这些全部属于未来 APP 的业务/运营数据，由未来 APP 自己生成和维护。

---

## 4. 数据库迁移 SQL

对当前已经存在的 `aimanga_v2` 数据库执行一次迁移：

```sql
ALTER TABLE `project`
  ADD COLUMN `content_uid` CHAR(36) DEFAULT NULL COMMENT '跨系统稳定作品ID' AFTER `id`,
  ADD COLUMN `description` TEXT COMMENT '漫画正式简介' AFTER `tagline`,
  ADD COLUMN `cover_url` VARCHAR(512) DEFAULT NULL COMMENT '漫画封面OSS URL' AFTER `description`,
  ADD COLUMN `category` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '漫画主分类' AFTER `cover_url`,
  ADD COLUMN `tags` JSON DEFAULT NULL COMMENT '漫画标签数组' AFTER `category`,
  ADD COLUMN `series_status` TINYINT NOT NULL DEFAULT 2 COMMENT '1连载中 2已完结' AFTER `tags`;

UPDATE `project`
SET `content_uid` = UUID()
WHERE `content_uid` IS NULL OR `content_uid` = '';

UPDATE `project`
SET `description` = `tagline`
WHERE (`description` IS NULL OR `description` = '')
  AND `tagline` IS NOT NULL
  AND `tagline` <> '';

ALTER TABLE `project`
  MODIFY COLUMN `content_uid` CHAR(36) NOT NULL COMMENT '跨系统稳定作品ID',
  ADD UNIQUE KEY `uk_project_content_uid` (`content_uid`);
```

同时修改 `v2/server/sql/schema.sql` 中 `project` 的完整建表定义，保证以后新环境直接建库时就包含上述字段。

### 4.1 `project` 目标结构

```sql
CREATE TABLE IF NOT EXISTS `project` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `content_uid` CHAR(36) NOT NULL COMMENT '跨系统稳定作品ID',
  `user_id` BIGINT UNSIGNED NOT NULL,
  `title` VARCHAR(255) NOT NULL,
  `source_text` LONGTEXT COMMENT '故事原文',
  `aspect_ratio` VARCHAR(20) NOT NULL DEFAULT '3:4' COMMENT '3:4/2:3/1:1/16:9',
  `color_mode` VARCHAR(20) NOT NULL DEFAULT 'partial' COMMENT 'partial局部上色/monochrome黑白/color全彩',
  `style_preset_id` BIGINT UNSIGNED DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0准备中1待出图2出图中3完成4部分失败',
  `tagline` VARCHAR(64) DEFAULT '' COMMENT '短简介/一句话卖点',
  `description` TEXT COMMENT '漫画正式简介',
  `cover_url` VARCHAR(512) DEFAULT NULL COMMENT '漫画封面OSS URL',
  `category` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '漫画主分类',
  `tags` JSON DEFAULT NULL COMMENT '漫画标签数组',
  `series_status` TINYINT NOT NULL DEFAULT 2 COMMENT '1连载中 2已完结',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_content_uid` (`content_uid`),
  KEY `idx_project_user` (`user_id`),
  KEY `idx_project_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作品';
```

---

## 5. Phase 3.5 — 后端代码修改要求

在正式进入 Phase 4 前完成本节。

### T3.5.1 修改 `Project` Entity

文件：

```text
v2/server/src/main/java/com/aimanga/v2/model/Project.java
```

新增：

```java
private String contentUid;
private String description;
private String coverUrl;
private String category;
/** JSON 数组字符串 */
private String tags;
/** 1连载中 2已完结 */
private Integer seriesStatus;
```

增加常量：

```java
public static final int SERIES_ONGOING = 1;
public static final int SERIES_COMPLETED = 2;
```

### T3.5.2 创建作品时生成 `content_uid`

修改：

```text
ProjectService.create(...)
```

创建任何新作品时必须生成 UUID：

```java
project.setContentUid(UUID.randomUUID().toString());
```

要求：

- TXT/DOCX 批量导入创建的每部作品也必须自动获得独立 `content_uid`。
- `content_uid` 创建后禁止因改标题、重新拆话、重跑脚本、重新出图而变化。
- 删除作品后重新导入同一源文件属于“新作品”，可以生成新的 `content_uid`。

### T3.5.3 修改 `ProjectVO`

新增返回字段：

```java
String contentUid
String description
String coverUrl
String category
String tags
Integer seriesStatus
```

`ProjectService.toVO()` 同步映射。

### T3.5.4 修改 `UpdateProjectRequest`

允许人工修改以下内容元数据：

```java
String tagline
String description
String coverUrl
String category
String tags
Integer seriesStatus
```

`contentUid` **禁止通过普通更新接口修改**。

### T3.5.5 前端最低改动

作品详情基础信息区域增加可编辑项：

- 短简介 tagline
- 正式简介 description
- 封面 coverUrl（可继续使用现有 OSS 上传能力）
- 主分类 category
- 标签 tags
- 连载状态 seriesStatus

Phase 3.5 不要求做复杂分类管理后台；`category` 暂时就是字符串，`tags` 是字符串数组。

---

## 6. Phase 4 任务系统如何处理

原 Phase 4 设计基本不变。

### 6.1 不新增任务类型

仍保持：

```text
SPLIT/SCRIPT/ASSET/SHEET/BATCH/PAGE/LAYOUT/COLORIZE/CLEAN/REPAINT
```

不要为了漫画元数据额外增加 `META` 任务，避免扩大任务系统改造范围。

### 6.2 Task 仍使用内部数据库 ID

任务调度继续使用：

```text
project_id
chapter_id
pageId
```

`content_uid` 不参与 Redis 队列、Worker 调度和内部外键关系。

原因：

- `id` 是生产系统内部高效主键。
- `content_uid` 只承担跨系统导入/导出身份。

### 6.3 Phase 4 验收新增一条回归

必须验证：

> 作品经过任务创建、停止、重试、服务重启恢复后，`project.content_uid` 始终不变化。

---

## 7. Phase 5 修改要求：生成并沉淀漫画元数据

原 Phase 5：

```text
T5.1 SPLIT + SCRIPT
T5.2 ASSET + SHEET
```

整体不变，只给 SPLIT 增加一个很小的职责：**生成作品级元数据。**

### 7.1 在 SPLIT 阶段生成以下字段

首次拆话时，根据整部 `project.source_text` 生成：

```text
tagline
  20～50 字左右，一句话卖点

description
  80～300 字左右，适合漫画详情页使用，避免直接剧透结局

category
  只输出一个主分类
  建议枚举范围：古风 / 都市 / 恋爱 / 悬疑 / 科幻 / 奇幻 / 热血 / 搞笑 / 治愈 / 校园 / 其他

tags
  3～8 个内容标签

series_status
  默认 2（已完结）
  只有明确识别为未完结/连载源文本时才设为 1
```

### 7.2 推荐 SPLIT AI 结构契约

不要要求 AI 返回数据库字段之外的运营数据。

推荐响应：

```json
{
  "metadata": {
    "tagline": "末日重临，他带着前世记忆抢占最后的生机",
    "description": "末世爆发前夕，曾在灾难中失去一切的林川意外重回三日前。面对即将到来的异变，他必须利用前世记忆提前囤积资源、寻找同伴，并改变曾经无法挽回的命运。",
    "category": "科幻",
    "tags": ["末世", "重生", "异能", "生存"],
    "seriesStatus": 2
  },
  "chapters": [
    {
      "chapterNo": 1,
      "title": "末日前三天",
      "scriptText": "..."
    }
  ]
}
```

如果当前 SPLIT 已经有自己的返回契约，可以在原契约中加入 `metadata`，不要推翻原有章节解析。

### 7.3 元数据写入规则

1. 第一次自动 SPLIT：允许 AI 写入元数据。
2. 用户已经人工修改过元数据后，重新 SPLIT **不要无条件覆盖**。
3. 最简单实现方式：
   - 只有字段为空时才自动补写；或
   - SPLIT payload 增加 `refreshMetadata=true` 时才强制覆盖。
4. `content_uid` 永远不由 AI 生成，也永远不被 SPLIT 修改。
5. `tags` 入库前必须序列化为合法 JSON 数组。
6. AI 返回未知 category 时规范成 `其他`。

### 7.4 SCRIPT / ASSET / SHEET

无需为了未来 APP 数据兼容做结构性修改。

继续按原 Phase 5 生成：

- chapter
- page
- character/scene/prop/outfit asset

这些生产数据照常使用。

---

## 8. Phase 6 修改要求：最终页与封面

未来 APP 真正需要的是**最终成品页**，因此必须明确：

```text
page.generated_image_url
```

是唯一的正式页图片字段。

### 8.1 BATCH 完成条件

一页只有满足：

```text
generate_status = 2
generated_image_url 非空
```

才算“可发布页”。

`layout_image_url` 永远不能作为未来 APP 漫画页导出。

### 8.2 后处理后的最终图

COLORIZE/CLEAN/REPAINT 完成并被用户接受后：

- 当前最终版本必须写回 `generated_image_url`。
- 老版本继续留在 `generate_records`。
- Phase 7 导出只读取当前 `generated_image_url`。

### 8.3 封面策略

当前 Phase 计划没有独立 COVER 任务，本次不强制增加新的 AI 封面生成流水线。

采用最低成本策略：

1. 如果用户已经人工上传/指定 `project.cover_url`，永远保留，不自动覆盖。
2. 如果 `cover_url` 为空，BATCH 整部完成后：
   - 查询本项目第一话开始的第一张成功成品页；
   - 按 `chapter_no ASC, page_no ASC` 排序；
   - 把该页 `generated_image_url` 写入 `project.cover_url`。
3. Phase 7 可以再提供“更换封面”入口，但不是导出兼容的阻塞项。

注意：不要因为页级并发而把“最先完成的随机页”当封面，必须按章节/页序查询第一张成功页。

---

## 9. Phase 7 修改要求：建立未来 APP 的唯一兼容边界

原 T7.2 计划只导出：

```text
作品/第N话/第M页.png
```

必须修改为：

```text
作品名/
├── manifest.json
├── 封面.png                 # 有封面时
├── 第1话/
│   ├── 第1页.png
│   ├── 第2页.png
│   └── ...
├── 第2话/
│   └── ...
└── ...
```

原图片 ZIP 能力保留，只是新增 `manifest.json`。

### 9.1 manifest 版本

固定：

```json
"schema_version": "comic-content-1.0"
```

以后如果内容协议变化，新增 1.1 / 2.0，不直接破坏 1.0。

### 9.2 manifest.json 标准

```json
{
  "schema_version": "comic-content-1.0",
  "content_uid": "2ed7e924-1d76-4b7a-93ad-6f976c815de8",
  "title": "末世重生",
  "tagline": "末日重临，他带着前世记忆抢占最后的生机",
  "description": "末世爆发前夕……",
  "cover_url": "https://xxx.oss-cn-xxx.aliyuncs.com/xxx/cover.png",
  "category": "科幻",
  "tags": ["末世", "重生", "异能", "生存"],
  "series_status": 2,
  "aspect_ratio": "3:4",
  "color_mode": "color",
  "chapters": [
    {
      "chapter_no": 1,
      "title": "末日前三天",
      "pages": [
        {
          "page_no": 1,
          "image_url": "https://xxx.oss-cn-xxx.aliyuncs.com/xxx/page_001.png",
          "file_path": "第1话/第1页.png"
        },
        {
          "page_no": 2,
          "image_url": "https://xxx.oss-cn-xxx.aliyuncs.com/xxx/page_002.png",
          "file_path": "第1话/第2页.png"
        }
      ]
    }
  ]
}
```

### 9.3 manifest 必须遵守的规则

1. `content_uid` 必须来自数据库 `project.content_uid`。
2. chapter 必须按 `chapter_no ASC` 排序。
3. page 必须按 `page_no ASC` 排序。
4. 只导出 `generate_status=2` 且 `generated_image_url` 非空的正式页。
5. 正常整部导出时，如存在失败/缺图页，应直接提示“作品未完整生成”，默认拒绝生成“正式发布包”。
6. 可另外保留 `allowPartial=true` 管理员参数用于调试导出，但 manifest 应标记：

```json
"complete": false
```

7. `tags` 在 JSON 中必须是数组，不是转义后的字符串。
8. 不导出 `user_id`。
9. 不导出生产 `status`。
10. 不导出 task。
11. 不导出 Prompt、fail_reason、generate_records。
12. 不导出 `style_preset_id` 作为未来 APP 外键。
13. 如希望展示风格，可在导出时通过 `style_preset_id` 查询名称，并仅增加：

```json
"style_name": "国漫"
```

但 `style_name` 属于可选字段，不影响 1.0 导入。

---

## 10. 未来漫画 APP 的导入 Mapping

未来 APP 数据库无需照抄本系统。

只需要实现 Importer：

| AIManga manifest | 未来 APP 示例字段 | 说明 |
|---|---|---|
| `content_uid` | `comic.content_uid` / `external_id` | 建议唯一索引 |
| `title` | `comic.title` | 必须 |
| `tagline` | `comic.short_description` | 可选 |
| `description` | `comic.description` | 必须 |
| `cover_url` | `comic.cover_url` | 必须 |
| `category` | 分类表/分类关系 | APP 自己映射 |
| `tags[]` | 标签表/标签关系 | APP 自己映射 |
| `series_status` | `comic.series_status` | 1连载/2完结 |
| `chapters[].chapter_no` | `comic_chapter.chapter_no` | 必须 |
| `chapters[].title` | `comic_chapter.title` | 必须 |
| `pages[].page_no` | `comic_page.page_no` | 必须 |
| `pages[].image_url` | `comic_page.image_url` | 必须 |

未来 APP 自己创建：

```text
comic.id
comic.author_id
chapter.id
page.id
publish_status
review_status
created_at
published_at
view_count
like_count
favorite_count
comment_count
```

### 10.1 官方作者处理

首批批量漫画导入 APP 时，未来 Importer 可以统一：

```text
author_id = 官方漫画账号 ID
source_type = OFFICIAL_AI / AI
```

因此当前 `project.user_id` 完全不需要迁移。

### 10.2 重复导入与更新

未来 APP 建议对 `content_uid` 建唯一索引。

首次导入：

```text
content_uid 不存在 → INSERT comic + chapters + pages
```

再次导入：

```text
content_uid 已存在 → UPDATE comic
                     → chapter 按 chapter_no UPSERT
                     → page 按 chapter_no + page_no UPSERT
```

因此不需要给当前 chapter/page 额外增加跨系统 UID。

---

## 11. Phase 计划需要同步修改的文档内容

本地 Agent 完成代码后，需要同步更新：

```text
v2/docs/02-技术架构设计.md
v2/docs/03-开发任务计划.md
v2/server/sql/schema.sql
```

### 11.1 `02-技术架构设计.md` §2 数据模型

把 project 描述改为：

```text
project(作品) id, content_uid UNIQUE, user_id, title, source_text, aspect_ratio,
              color_mode, style_preset_id?, status, tagline, description, cover_url,
              category, tags JSON, series_status, create_time, update_time
```

并增加说明：

> `content_uid` 是未来 APP/小程序导入时使用的稳定作品标识；内部任务与外键继续使用自增 `id`。`status` 为生产状态，`series_status` 为作品内容状态，两者不得混用。

### 11.2 `03-开发任务计划.md`

在 Phase 3 与 Phase 4 中间加入：

```text
## Phase 3.5 漫画内容兼容层

### T3.5.1 作品元数据字段 + content_uid
- 修改 project 表、Entity/VO/Update DTO
- 存量作品回填 UUID
- 新作品自动生成 UUID
- 作品详情支持维护简介/封面/分类/标签/连载状态
- content_uid 不可编辑
```

修改 T5.1：

```text
SPLIT 除拆话外，首次处理时生成 tagline/description/category/tags/series_status，
字段已有人工值时默认不覆盖。
```

修改 T6.1：

```text
整部 BATCH 完成后，如 project.cover_url 为空，按 chapter_no/page_no 顺序取第一张成功成品页作为默认封面。
```

修改 T7.2：

```text
导出 ZIP 除原有按话图片目录外，根目录新增 manifest.json，遵循 comic-content-1.0；
未来 APP 仅依赖 manifest 的漫画核心内容字段，不依赖生产系统内部 ID/用户/任务字段。
```

---

## 12. 推荐代码新增结构

为避免 Phase 7 临时拼 JSON，建议现在就定义内容导出 DTO，但不需要提前实现导出接口。

建议目录：

```text
com.aimanga.v2.dto.export
├── ComicManifest.java
├── ComicManifestChapter.java
└── ComicManifestPage.java
```

建议结构：

```java
public record ComicManifest(
        String schemaVersion,
        String contentUid,
        String title,
        String tagline,
        String description,
        String coverUrl,
        String category,
        List<String> tags,
        Integer seriesStatus,
        String aspectRatio,
        String colorMode,
        Boolean complete,
        List<ComicManifestChapter> chapters) {
}

public record ComicManifestChapter(
        Integer chapterNo,
        String title,
        List<ComicManifestPage> pages) {
}

public record ComicManifestPage(
        Integer pageNo,
        String imageUrl,
        String filePath) {
}
```

注意：数据库 Entity 中 `tags` 可继续按当前项目 JSON 字段习惯使用 `String`；导出 DTO 中应解析为 `List<String>`。

---

## 13. 验收标准

Phase 3.5 完成后必须逐项通过。

### 数据库

- [ ] 当前存量 project 全部有非空、唯一 `content_uid`。
- [ ] 新建 project 自动拥有 UUID。
- [ ] TXT/DOCX 批量导入的多部作品 `content_uid` 互不重复。
- [ ] `description / cover_url / category / tags / series_status` 可正常读写。
- [ ] 原有数据、原有 CRUD 无损。

### 稳定性

- [ ] 修改标题后 content_uid 不变。
- [ ] 重拆话后 content_uid 不变。
- [ ] 删除/重建 chapter/page 不影响 content_uid。
- [ ] Phase 4 task retry/recovery 不影响 content_uid。

### Phase 5

- [ ] SPLIT 能输出并保存项目元数据。
- [ ] tags 永远是合法 JSON 数组。
- [ ] category 不在约定分类时变为“其他”。
- [ ] 已人工填写的元数据默认不会被重跑 SPLIT 覆盖。

### Phase 6

- [ ] 每个正式漫画页以 `generated_image_url` 为准。
- [ ] 后处理确认后的新图能成为当前 `generated_image_url`。
- [ ] cover_url 为空时整部生成完成后可自动得到默认封面。
- [ ] 已手工设置 cover_url 时不会被 BATCH 覆盖。

### Phase 7

- [ ] ZIP 保持原来的“按话/按页图片”结构。
- [ ] ZIP 根目录存在 `manifest.json`。
- [ ] manifest 符合 `comic-content-1.0`。
- [ ] manifest 不包含 `user_id`、task、Prompt、失败日志等生产字段。
- [ ] chapters/pages 顺序确定且稳定。
- [ ] 缺少正式成品页时，默认阻止生成正式发布包。
- [ ] 使用 manifest 可以在一个空数据库中重建“漫画 → 话 → 页”的完整阅读结构。

---

## 14. 明确禁止事项

本次改造请勿做以下事情：

1. 不要把 `project` 改名为 `comic`。
2. 不要把整个数据库按未来 APP 重构。
3. 不要删除现有生产字段。
4. 不要修改 `project.status` 的现有生产状态语义。
5. 不要用 `project.user_id` 作为未来漫画作者 ID。
6. 不要要求未来 APP 沿用当前 `project.id/chapter.id/page.id`。
7. 不要新增点赞/评论/收藏/审核/推荐/付费等 APP 表。
8. 不要为了元数据引入新的 Task 类型。
9. 不要修改当前 OSS URL 存储方案。
10. 不要让 AI 生成或修改 `content_uid`。

---

## 15. 本次 Agent 执行顺序

严格按以下顺序执行：

```text
1. 备份当前 aimanga_v2 数据库
2. 创建一次性 migration SQL
3. 修改 server/sql/schema.sql
4. 执行 migration，并检查存量 content_uid
5. 修改 Project Entity
6. 修改 ProjectVO
7. 修改 UpdateProjectRequest
8. 修改 ProjectService.create / toVO / update
9. 修改前端 Project 类型与作品详情元数据编辑
10. 增加对应后端测试
11. 运行 mvn test
12. 运行 frontend tsc --noEmit + npm run build
13. 更新 docs/02-技术架构设计.md
14. 更新 docs/03-开发任务计划.md，插入 Phase 3.5 并修订 T5.1/T6.1/T7.2
15. 提交本阶段结果给用户审核
16. 审核通过后，再开始原 Phase 4 T4.1
```

建议 git commit：

```text
v2(T3.5): add portable comic content metadata and export contract
```

---

## 16. 最终数据边界总结

### 当前批量生产系统负责保存

```text
Project
  content_uid
  title
  tagline
  description
  cover_url
  category
  tags
  series_status

Chapter
  chapter_no
  title

Page
  page_no
  generated_image_url
```

### 当前系统继续保存，但未来 APP 可忽略

```text
user_id
source_text
生产 status
style_preset_id
chapter.script_text
page.narration/dialogue/visual/scene_description
layout_image_url
generate_status/fail_reason/generate_records
asset
task
system_config
```

### 未来 APP 自己负责

```text
APP 内部 comic/chapter/page 主键
author_id
发布/审核状态
点赞/评论/收藏/浏览
推荐
付费
用户关系
运营字段
```

最终兼容链路：

```text
AIMangaStudio v2
     │
     │  project/chapter/page
     ▼
comic-content-1.0 manifest
     │
     ▼
未来 APP Importer
     │
     ▼
未来 APP 自己的数据库结构
```

**只要 `comic-content-1.0` 协议稳定，未来 APP 数据库无需与当前 AIMangaStudio v2 表结构一致。**
