package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task")
public class TaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long projectId;

    private Long chapterId;

    /** SPLIT/SCRIPT/ASSET/SHEET/BATCH/PAGE/LAYOUT/COLORIZE/CLEAN/REPAINT/MOCK */
    private String taskType;

    private Integer status;

    private Integer priority;

    /** 0-100 */
    private Integer progress;

    private Integer totalCount;

    private Integer successCount;

    private Integer failCount;

    /** 当前处理页/断点 */
    private Integer currentNo;

    /** 任务参数 JSON(steps/sleepMs/pageId/colorMode...) */
    private String payload;

    private String error;

    /** 任务结果 JSON(如 failedPages) */
    private String result;

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** Worker 心跳(看门狗据此判定僵尸任务) */
    private LocalDateTime heartbeatTime;

    /** 任务执行锁:领取时生成,终态写入时校验,防止双 Worker/看门狗互踩 */
    private String claimToken;

    /** 执行租约截止(Phase 8.4 多实例安全) */
    private java.time.LocalDateTime leaseUntil;

    /** 执行实例(Phase 8.4) */
    private String workerInstanceId;

    /** 单次 Attempt 最大执行时间(秒) */
    private Integer maxExecutionSeconds;

    /** 看门狗自动重试次数 */
    private Integer retryCount;

    /** 最大自动重试次数 */
    private Integer maxRetryCount;

    /** 心跳超时阈值(秒),超过判定僵尸 */
    private Integer timeoutSeconds;

    /** 已处理步数(成功+失败) */
    private Integer processedCount;

    /** 最后一次错误记录 */
    private String lastError;

    /** 单任务暂停意图；与项目暂停意图独立。 */
    private Boolean pauseRequested;

    private Long controlVersion;
}
