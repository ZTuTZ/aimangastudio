package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** A fixed business target captured when a task is admitted. */
@Data
@TableName("task_plan_unit")
public class TaskPlanUnit {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELLED = 4;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer planVersion;
    private String stageType;
    private String businessType;
    private Long businessId;
    private Integer sourceScriptVersion;
    private Long sourceRevision;
    private String inputSnapshot;
    private Integer status;
    private Integer retryCount;
    private String attemptToken;
    private String resultRef;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime finishTime;
}
