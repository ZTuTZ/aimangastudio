package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** AI 生成记录(Phase 6.7):每次 LAYOUT/PAGE/后处理的完整生成上下文,供回溯与排查 */
@Data
@TableName("generation_record")
public class GenerationRecord {

    public static final String KIND_LAYOUT = "LAYOUT";
    public static final String KIND_PAGE = "PAGE";
    public static final String KIND_COLORIZE = "COLORIZE";
    public static final String KIND_CLEAN = "CLEAN";
    public static final String KIND_REPAINT = "REPAINT";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long chapterId;

    private Long pageId;

    private Long taskId;

    /** LAYOUT/PAGE/COLORIZE/CLEAN/REPAINT */
    private String kind;

    private String model;

    private String prompt;

    /** 参考图 URL 数组 JSON */
    private String referenceUrls;

    /** 输入图(后处理为原图) */
    private String inputUrl;

    private String resultUrl;

    private String status;

    private String error;

    private LocalDateTime createTime;
}
