package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project")
public class Project {

    /** 0准备中 1待出图 2出图中 3完成 4部分失败 */
    public static final int STATUS_PREPARING = 0;
    public static final int STATUS_READY = 1;
    public static final int STATUS_GENERATING = 2;
    public static final int STATUS_DONE = 3;
    public static final int STATUS_PARTIAL = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String sourceText;

    /** 3:4 / 2:3 / 1:1 / 16:9 */
    private String aspectRatio;

    /** partial / monochrome / color */
    private String colorMode;

    private Long stylePresetId;

    private Integer status;

    private String tagline;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
