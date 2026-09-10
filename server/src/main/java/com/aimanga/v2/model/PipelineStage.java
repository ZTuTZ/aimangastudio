package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 流水线阶段进度(Phase 5.7 断点恢复) */
@Data
@TableName("comic_pipeline_stage")
public class PipelineStage {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_PAUSED = 4;
    public static final int STATUS_STOPPED = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** SPLIT/ASSET/SCRIPT/SHEET/LAYOUT/IMAGE/EXPORT */
    private String stageType;

    private Integer status;

    private Integer progress;

    private Integer totalCount;

    private Integer successCount;

    private Integer failedCount;

    /** 阶段结果引用(如 chapterIds/assetIds) */
    private String resultRef;

    private String error;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
