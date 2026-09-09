package com.aimanga.v2.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 作品视图。
 * 注意:必须是可设值类而非 record —— MyBatis 结果集映射(record 走列序构造器注入)不可靠。
 */
@Data
public class ProjectVO {

    private Long id;

    private String title;

    private Integer status;

    private String aspectRatio;

    private String colorMode;

    private Long stylePresetId;

    private String tagline;

    private String sourceText;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long chapterCount;

    private Long pageCount;
}
