package com.aimanga.v2.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 任务视图(MyBatis 映射目标,必须可设值类) */
@Data
public class TaskVO {

    private Long id;

    private Long userId;

    private Long projectId;

    private Long chapterId;

    private String taskType;

    private Integer status;

    private Integer priority;

    private Integer progress;

    private Integer totalCount;

    private Integer successCount;

    private Integer failCount;

    private Integer currentNo;

    private String payload;

    private String error;

    private String result;

    private LocalDateTime createTime;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String projectTitle;
}
