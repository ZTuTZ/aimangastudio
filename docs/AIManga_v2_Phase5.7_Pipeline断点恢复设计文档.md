# AIManga v2 Phase 5.7

# Pipeline断点恢复与暂停继续执行设计文档

## 目标

在 Phase 5.6 任务可靠性的基础上，实现：

-   服务重启后继续执行
-   用户暂停后继续执行
-   阶段失败后恢复
-   已完成结果不重复生成
-   大规模漫画生产可恢复

核心原则：

> 已完成工作必须持久化，恢复时只执行未完成部分。

------------------------------------------------------------------------

# 1. 当前问题

当前任务系统如果重启后重新创建流程，会导致：

-   已完成SPLIT重复执行
-   已完成ASSET重复执行
-   已生成页面重复生成
-   AI成本浪费

用户暂停后继续执行时，也无法准确知道：

-   哪些阶段完成
-   哪些子任务完成
-   哪些任务需要继续

------------------------------------------------------------------------

# 2. 设计原则

## Task负责调度

Task代表一次漫画生产请求。

## Pipeline Stage负责业务阶段状态

漫画流程：

SPLIT

ASSET

SCRIPT

LAYOUT

IMAGE

EXPORT

## 子任务独立持久化

例如图片生成：

不能内存循环100页。

必须：

page1任务

page2任务

page3任务

分别保存状态。

------------------------------------------------------------------------

# 3. 新增Pipeline Stage

新增：

comic_pipeline_stage

字段：

``` sql
id
project_id
stage_type
status
progress
total_count
success_count
failed_count
result_ref
start_time
finish_time
create_time
update_time
```

stage_type：

    SPLIT
    ASSET
    SCRIPT
    LAYOUT
    IMAGE
    EXPORT

status：

    PENDING
    RUNNING
    SUCCESS
    FAILED
    PAUSED
    STOPPED

------------------------------------------------------------------------

# 4. 恢复流程

系统启动：

    Application Start

    ↓

    Recovery Service

    ↓

    查询未完成Pipeline

    ↓

    检查Stage状态

    ↓

    恢复未完成阶段

    ↓

    重新进入Task Queue

如果：

    SPLIT SUCCESS
    ASSET SUCCESS
    SCRIPT RUNNING

恢复后：

直接继续SCRIPT。

不会重新执行SPLIT和ASSET。

------------------------------------------------------------------------

# 5. Checkpoint机制

每个阶段完成必须：

1.  保存业务结果
2.  更新Stage SUCCESS

例如：

SPLIT：

    AI拆话

    ↓

    保存chapter

    ↓

    SPLIT SUCCESS

禁止：

    AI结果只保存在内存

------------------------------------------------------------------------

# 6. 子任务断点

IMAGE阶段：

需要保存：

page_generation_task

包含：

    project_id
    chapter_id
    page_id
    task_type
    status
    retry_count
    result_url

恢复：

查询：

    status != SUCCESS

继续执行。

------------------------------------------------------------------------

# 7. 用户暂停继续

暂停：

    RUNNING

    ↓

    PAUSING

    ↓

    PAUSED

暂停规则：

-   不再领取新任务
-   未开始任务保持PENDING
-   正在执行AI请求等待返回后保存结果

继续：

    PAUSED

    ↓

    RUNNING

重新扫描：

-   未完成Stage
-   未完成子任务

继续执行。

------------------------------------------------------------------------

# 8. 幂等要求

所有生成任务必须支持重复调用。

例如：

page生成前检查：

    page_generation_task.status == SUCCESS

如果成功：

直接跳过。

禁止：

同一页产生多个正式结果。

------------------------------------------------------------------------

# 9. 状态恢复测试

必须测试：

## 服务重启

SPLIT完成，SCRIPT执行中。

重启后：

继续SCRIPT。

## 暂停继续

IMAGE生成到一半暂停。

继续后：

已完成页面跳过。

## 单页失败

page50失败。

重新执行page50。

page1-page49不重新生成。

## 多实例恢复

两个服务同时启动。

同一个Pipeline只能恢复一次。

------------------------------------------------------------------------

# 10. 与Phase5.6关系

Phase5.6：

解决：

-   Worker可靠执行
-   heartbeat
-   watchdog
-   Redis恢复
-   retry

Phase5.7：

解决：

-   Pipeline位置
-   阶段恢复
-   用户暂停继续
-   子任务断点

最终：

    Pipeline
       |
    Task
       |
    Worker

------------------------------------------------------------------------

# Phase5.7完成后进入Phase6

最终顺序：

Phase5.5 长剧本拆话、资产、脚本一致性

↓

Phase5.6 任务可靠性

↓

Phase5.7 Pipeline断点恢复

↓

Phase6 漫画页生成
