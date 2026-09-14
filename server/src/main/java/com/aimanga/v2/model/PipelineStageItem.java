package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 流水线阶段执行单元(Phase 5.8):SCRIPT 等阶段内部的章节/页级断点 */
@Data
@TableName("pipeline_stage_item")
public class PipelineStageItem {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** SPLIT/ASSET/SCRIPT/SHEET/LAYOUT/IMAGE/EXPORT */
    private String stageType;

    /** CHAPTER/PAGE */
    private String businessType;

    private Long businessId;

    private Integer status;

    private Integer retryCount;

    /** 结果引用 JSON */
    private String resultRef;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
