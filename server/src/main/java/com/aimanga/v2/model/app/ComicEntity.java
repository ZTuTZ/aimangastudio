package com.aimanga.v2.model.app;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** [APP 模拟]漫画(Phase 7.6):与生产库完全隔离的阅读库 */
@Data
@TableName("comic")
public class ComicEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String contentUid;

    private String title;

    private String tagline;

    private String description;

    private String coverUrl;

    private String category;

    private String tags;

    private Integer seriesStatus;

    private String aspectRatio;

    private String colorMode;

    private Integer complete;

    private LocalDateTime createTime;
}
