# AIManga v2 Phase 5.8

# Pipeline Stage Item 化改造执行文档

## 目标

在 Phase 5.6 任务可靠性和 Phase 5.7
Pipeline断点恢复基础上，调整任务模型。

核心目标：

> Task表示一次漫画生产流程，Stage Item表示阶段内部具体执行单元。

解决：

-   SPLIT完成后产生大量SCRIPT Task
-   暂停恢复复杂
-   阶段进度无法统一统计
-   大规模生产任务数量膨胀

------------------------------------------------------------------------

# 1. 当前问题

当前：

Comic Task → SPLIT → SCRIPT Task × N → LAYOUT Task × N → IMAGE Task × N

例如20话漫画会产生20个SCRIPT任务。

问题：

1.  漫画整体状态分散。
2.  暂停需要处理大量任务。
3.  恢复无法准确定位阶段进度。
4.  批量生产时任务数量爆炸。

------------------------------------------------------------------------

# 2. 新架构

改为：

Comic Pipeline Task

↓

Pipeline Stage

↓

Stage Item

示例：

SCRIPT Stage

包含：

chapter1 Item chapter2 Item ... chapter20 Item

------------------------------------------------------------------------

# 3. 数据模型

新增：

pipeline_stage_item

字段：

``` sql
id
project_id
stage_type
business_type
business_id
status
retry_count
result_ref
error_message
create_time
update_time
```

用途：

保存：

-   某一话脚本生成状态
-   某一页图片生成状态
-   单个失败重试信息

------------------------------------------------------------------------

# 4. Task职责

Task只代表：

一次漫画生产请求。

例如：

生成《末世觉醒》。

不再代表：

生成第1话脚本。

------------------------------------------------------------------------

# 5. Stage职责

Stage表示阶段：

    SPLIT
    ASSET
    SCRIPT
    LAYOUT
    IMAGE
    EXPORT

保存：

-   阶段状态
-   总数量
-   成功数量
-   当前进度

例如：

SCRIPT:

    total=20
    success=8
    progress=8/20

------------------------------------------------------------------------

# 6. SPLIT修改

旧：

SPLIT完成

↓

创建20个SCRIPT Task

新：

SPLIT完成

↓

创建SCRIPT Stage

↓

创建20个SCRIPT Stage Item

例如：

    chapter1 PENDING
    chapter2 PENDING
    ...
    chapter20 PENDING

------------------------------------------------------------------------

# 7. Worker调整

Worker不直接执行章节任务。

改为：

Worker

↓

StageRunner

↓

StageItem

↓

Handler

StageRunner负责：

-   获取未完成Item
-   控制并发
-   更新Item状态
-   汇总Stage进度

------------------------------------------------------------------------

# 8. 暂停继续

暂停：

Pipeline：

RUNNING

↓

PAUSED

未完成Item：

保持PENDING。

已完成Item：

保持SUCCESS。

继续：

重新扫描：

status != SUCCESS

继续执行。

------------------------------------------------------------------------

# 9. 重启恢复

启动：

Recovery Service

↓

恢复Pipeline

↓

检查Stage

↓

恢复未完成Stage Item

不会重新执行：

已SUCCESS阶段。

------------------------------------------------------------------------

# 10. 幂等要求

所有Stage Item必须支持重复执行。

例如：

chapter_id=10脚本已经成功。

再次执行：

直接跳过。

禁止：

重复生成正式内容。

------------------------------------------------------------------------

# 11. 验收测试

## 测试1

上传20话剧本。

期望：

1个Comic Task

1个SCRIPT Stage

20个SCRIPT Item

不是：

20个SCRIPT Task。

## 测试2

SCRIPT完成8/20。

重启服务。

恢复：

继续9/20。

## 测试3

暂停后继续。

已完成内容不重新生成。

## 测试4

第5话失败。

只重试第5话。

------------------------------------------------------------------------

# 12. Phase顺序

Phase5.5

长剧本、资产、脚本一致性

↓

Phase5.6

任务可靠性

↓

Phase5.7

Pipeline断点恢复

↓

Phase5.8

Pipeline Stage Item化

↓

Phase6

漫画页生成

------------------------------------------------------------------------

最终架构：

Comic Pipeline

↓

Pipeline Stage

↓

Stage Item

↓

Worker

↓

Generation Service

支持：

-   批量生产
-   暂停继续
-   重启恢复
-   单章节重试
-   单页面重试
-   不重复消耗AI资源
