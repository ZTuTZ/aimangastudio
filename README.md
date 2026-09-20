# AIMangaStudio v2

AI 漫画批量生产平台。上传长篇剧本，自动完成 拆话 → 资产提取 → 分话脚本，用户按需生成角色/场景素材，一键高并发出成品漫画页，并支持发布校验与 `comic-content-1.0` 标准导出。

> 当前仓库只包含 v2 内容（v1 旧版不在本仓库）。开发主线在本地 monorepo 的 `v2/` 目录，通过 `git subtree` 同步到本仓库，`v2/` 即仓库根目录。

## 核心特性

- **自动准备流水线**：SPLIT 拆话（AI 只定边界、Java 按 offset 切原文、全文覆盖硬校验）→ ASSET 资产提取（角色/场景/道具/服装）→ SCRIPT 六段式分镜脚本（页级 Source Spine 覆盖、全书资产注入）→ 项目「待出图」
- **素材工作台**：角色六姿势设定表（SHEET）、场景/道具/服装概念参考图（ASSET_REF），用户在资产库勾选后批量并发生成
- **并发生图引擎**：`ConcurrentStageRunner` + 独立 Worker 池（脚本/生图分离），Stage Item 原子领取、单 Item 失败自动重试、暂停/继续、服务重启断点恢复
- **生成成品工作台**：素材出图预检（缺必需角色素材阻止出图）、整部/单话/多话批量 BATCH（1 任务 → LAYOUT + IMAGE 两阶段 N 页 Item）、实时阶段进度、页画廊
- **对白层（Text Layer）**：底图与文字解耦，`comic-text-layer-1.0` 归一化坐标协议；Web 端拖拽/缩放编辑气泡并保存，导出包携带布局数据供未来 APP 渲染
- **发布与导出**：`PublicationValidator` 发布校验（话序/页号连续、成品就绪）→ `comic-content-1.0` ZIP 导出（manifest.json + 成品页图片）→ 批量导出与 APP Importer 模拟
- **任务可靠性**：MySQL 任务事实源 + Redis 队列，claim_token 原子领取、30s 心跳、看门狗僵尸接管、Pending 补偿、优雅停机与毒丸清理

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17/21 · Spring Boot 3.3.5 · Apache Shiro 2(jwt) · MyBatis-Plus 3.5.7 · Redisson 3.35 · MySQL 8 |
| 前端 | React 18 · Vite 5 · Ant Design 5 · TanStack Query 5 · Zustand · TailwindCSS |
| AI | 双协议网关（Gemini generateContent / OpenAI chat.completions），文本/生图/编辑三通道 |
| 存储 | 阿里云 OSS（所有生成图统一转存） |

## 项目结构

```text
server/                        后端(Spring Boot, 端口 8090)
  src/main/java/com/aimanga/v2/
    task/                      任务系统(Worker池/队列/看门狗/恢复)
    pipeline/                  生产流水线(SPLIT/ASSET/SCRIPT/SHEET/ASSET_REF/LAYOUT/IMAGE/BATCH/后处理)
      text/                    原文确定性索引
      split/                   滚动分包拆话规划
    ai/                        AI 双协议客户端与限流
    controller/ service/ dto/ model/ repository/
  sql/                         schema.sql/seed.sql(人工参考，正式变更走Flyway)
  src/test/                    单元测试 + comic-text-layer-v1 fixtures
frontend/                      前端(React, 端口 5173)
docs/                          各 Phase 执行文档与迁移 SQL
```

## 快速开始

### 1. 数据库

```bash
# 先创建空库并配置 DB_HOST、DB_PORT、DB_NAME、DB_USERNAME、DB_PASSWORD。
# 后端启动时 Flyway 会按 server/src/main/resources/db/migration 自动建表和升级。
cd server
mvn spring-boot:run
```

`server/sql/schema.sql` 与 `server/sql/seed.sql` 仅用于结构和默认值参考，正式升级不要手工修改或重放历史迁移。

### 2. 后端

```bash
cd server
# 敏感配置走环境变量或 src/main/resources/application-local.yml(已 gitignore,自行创建)
# 需要: DB_HOST/DB_PASSWORD、REDIS_HOST、JWT_SECRET(必填)、OSS 与 AI 密钥(也可登录后在配置中心填)
mvn spring-boot:run
```

### 3. 前端

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173,/api 代理到 8090
```

### 4. 初始化

- 管理员账号由启动引导创建(`AdminBootstrap`),登录后进入 **系统配置** 填写 AI 网关地址/密钥与 OSS
- 上传剧本(TXT/DOCX/粘贴) → 自动流水线 → 资产库勾选生成素材 → 「生成成品」页签一键出图

## 关键配置(system_config,管理后台热更新)

| 键 | 默认 | 说明 |
|---|---|---|
| `task_max_concurrency` | 5 | 全局并行任务数(层①) |
| `task_user_concurrency` | 2 | 每用户并行任务数(层②) |
| `script_item_concurrency` | 5 | 脚本阶段并发(独立池) |
| `image_generation_concurrency` | 5 | 生图阶段并发(独立池,热更新) |
| `image_gen_max_retry` | 3 | 单图失败自动重试次数 |
| `page_direct_output` | 0 | 1=跳过布局阶段直接出成品页 |
| `page_generation_asset_gate` | 1 | 1=缺必需角色素材阻止出图;0=仅警告 |
| `page_reference_max_images` | 8 | 单页最大参考图数量 |
| `feature_auto_split / feature_auto_asset` | 1 | 自动拆话/提取开关 |
| `feature_auto_sheet` | 0 | 自动生成全部设定表(默认关闭,素材由用户勾选) |

生产环境还应核对任务租约、心跳、执行时限、导出配额和 AI 超时。完整配置及升级步骤见
[`docs/Phase8_生产部署与迁移说明_2026-09-20.md`](docs/Phase8_生产部署与迁移说明_2026-09-20.md)。

## 导出协议

| 协议 | 用途 |
|---|---|
| `comic-content-1.0` | 发布包:manifest.json(comic/chapter/page) + 成品页图片 ZIP |
| `comic-text-layer-1.0` | 页动态对白层:归一化坐标气泡/旁白框,底图与文字解耦,未来 APP 直接消费 |

测试夹具见 `server/src/test/resources/fixtures/comic-text-layer-v1/`。

## 验收与文档

- 各阶段执行文档与迁移 SQL 见 `docs/`
- 端到端人工验收清单见 `docs/AIManga_v2_Phase7.7_端到端验收清单.md`
- Phase 8 生产部署、旧任务过渡和回滚说明见 `docs/Phase8_生产部署与迁移说明_2026-09-20.md`
