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
}
