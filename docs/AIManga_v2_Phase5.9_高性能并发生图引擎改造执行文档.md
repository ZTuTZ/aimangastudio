# AIManga v2 Phase 5.9

# 高性能并发生图引擎改造执行文档

## 背景

Phase 5.8 完成后，系统已经具备：

-   Pipeline
-   Stage
-   Stage Item
-   断点恢复
-   暂停继续

但是当前生图阶段仍存在串行执行问题。

典型表现：

    IMAGE Stage

    Item1
    执行生图
    等待AI返回
    保存OSS

    Item2
    执行生图
    等待AI返回

    Item3
    执行生图
    ...

导致：

-   GPU/API资源利用率低
-   大量页面生成耗时过长
-   无法满足批量漫画生产需求

目标：

建立可靠的并发生图模式：

    IMAGE Stage

            |
            |

    Stage Item Pool

            |
            |

    Worker Thread Pool

            |
            |

    AI Image API

支持：

-   多页同时生成
-   单页失败重试
-   暂停继续
-   服务重启恢复
-   限制并发避免API/服务器过载

------------------------------------------------------------------------

# 1. 核心设计原则

## 1.1 Pipeline仍然只有一个IMAGE Stage

不要创建：

    IMAGE Task × 200

保持：

    IMAGE Stage

    total=200
    success=0

------------------------------------------------------------------------

## 1.2 Stage Item负责并发单元

例如：

    IMAGE Stage

    page1 Item
    page2 Item
    page3 Item
    ...
    page200 Item

每个Item独立状态：

    PENDING
    RUNNING
    SUCCESS
    FAILED

------------------------------------------------------------------------

## 1.3 Worker池执行Item

不要：

    for(item){
     generate()
    }

改：

    submit(item)

    submit(item)

    submit(item)

由线程池调度。

------------------------------------------------------------------------

# 2. 新增ImageStageRunner

新增：

    ImageStageRunner

职责：

1.  查询IMAGE阶段未完成Item。
2.  按并发限制提交任务。
3.  等待执行结果。
4.  更新Item状态。
5.  更新Stage进度。

------------------------------------------------------------------------

伪代码：

``` java
List<Item> items = getPendingItems();

for(Item item:items){

    executor.submit(() -> {

        generatePage(item);

    });

}
```

------------------------------------------------------------------------

# 3. 并发线程池设计

禁止：

    new Thread()

每个图片创建线程。

使用：

    ThreadPoolExecutor

配置：

``` yaml
image:
  concurrency: 5
  queue-size: 50
```

默认：

    5并发

原因：

单台服务器不要无限并发。

------------------------------------------------------------------------

# 4. 动态并发控制

增加配置：

    image_generation_concurrency

运行时可调整：

例如：

低配置：

    2

高配置：

    20

修改配置后：

线程池平滑扩容。

------------------------------------------------------------------------

# 5. Item状态抢占

多个Worker不能生成同一页。

增加：

PipelineStageItem claim机制。

领取：

    PENDING

    ↓

    RUNNING

必须：

数据库原子更新。

例如：

``` sql
UPDATE pipeline_stage_item

SET

status='RUNNING'

WHERE id=?

AND status='PENDING'
```

影响行数：

1:

成功领取。

0:

已经被其他Worker领取。

------------------------------------------------------------------------

# 6. 失败重试

单页生成：

例如：

page20失败。

不要影响：

page1-page19。

流程：

    FAILED

    ↓

    retry_count++

    ↓

    PENDING

    ↓

    重新执行

默认：

    max_retry=3

------------------------------------------------------------------------

# 7. 暂停支持

用户暂停：

IMAGE Stage:

    RUNNING

    ↓

    PAUSED

规则：

-   不再领取新的Item
-   已RUNNING请求等待返回
-   返回后保存结果

继续：

查询：

    status != SUCCESS

继续生成。

------------------------------------------------------------------------

# 8. 服务重启恢复

启动：

Recovery扫描：

    IMAGE Stage

恢复：

    SUCCESS:
    跳过


    RUNNING:
    检查heartbeat


    PENDING:
    重新执行

------------------------------------------------------------------------

# 9. OSS保存幂等

生图成功后：

流程：

    AI返回图片

    ↓

    保存OSS

    ↓

    更新page.generated_image_url

    ↓

    Item SUCCESS

禁止：

先标SUCCESS再保存OSS。

------------------------------------------------------------------------

# 10. 进度统计

IMAGE Stage:

维护：

    total_count

    success_count

    failed_count

例如：

    图片生成:

    85/200

来源：

    success_count / total_count

------------------------------------------------------------------------

# 11. 不建议方案

禁止：

## 方案1

一个Task生成全部页面：

    for page:
     generate

原因：

无法并发。

## 方案2

创建200个独立Task。

原因：

破坏Pipeline模型。

## 方案3

无限线程。

原因：

API限制、数据库压力、内存风险。

------------------------------------------------------------------------

# 12. 需要修改模块

## PipelineStageService

增加：

-   item claim
-   item running锁

## Worker

支持：

StageRunner提交并发执行。

## Image生成Handler

改：

单Item执行。

输入：

    PipelineStageItem

输出：

    page result

## 配置中心

增加：

    image_generation_concurrency

------------------------------------------------------------------------

# 13. 验收测试

## 测试1

100页漫画。

配置：

    concurrency=5

结果：

同时最多5个AI请求。

------------------------------------------------------------------------

## 测试2

第30页失败。

结果：

其他页面继续。

只重试第30页。

------------------------------------------------------------------------

## 测试3

生成50页时暂停。

结果：

已完成页面保留。

继续后：

从未完成页面开始。

------------------------------------------------------------------------

## 测试4

服务器重启。

结果：

SUCCESS页面跳过。

RUNNING/PENDING恢复。

------------------------------------------------------------------------

# Phase顺序

Phase5.5

内容一致性

↓

Phase5.6

任务可靠性

↓

Phase5.7

断点恢复

↓

Phase5.8

Stage Item化

↓

Phase5.9

并发生图引擎

↓

Phase6

漫画生产
