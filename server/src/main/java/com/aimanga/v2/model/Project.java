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

    /** 内容连载状态:1连载中 2已完结(与生产状态 status 严格分离) */
    public static final int SERIES_ONGOING = 1;
    public static final int SERIES_COMPLETED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 跨系统稳定作品 ID(创建后永久不变,禁止 AI 生成/修改;内部调度仍用 id) */
    private String contentUid;

    private Long userId;

    private String title;

    private String sourceText;

    /** 3:4 / 2:3 / 1:1 / 16:9 */
    private String aspectRatio;

    /** 素材参考图画幅:场景(默认 16:9) */
    private String sceneRatio;

    /** 素材参考图画幅:道具(默认 1:1) */
    private String propRatio;

    /** 素材参考图画幅:服装(默认 3:4) */
    private String costumeRatio;

    /** partial / monochrome / color */
    private String colorMode;

    private Long stylePresetId;

    private Integer status;

    private String tagline;

    /** 漫画正式简介 */
    private String description;

    /** 漫画封面 OSS URL */
    private String coverUrl;

    /** 漫画主分类(如 悬疑/古风/科幻) */
    private String category;

    /** 标签 JSON 数组字符串,如 ["重生","系统"] */
    private String tags;

    /** 1连载中 2已完结 */
    private Integer seriesStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
