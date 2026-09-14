package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chapter")
public class Chapter {

    /** 0待处理 1脚本生成中 2脚本就绪 3出图中 4完成 5部分失败 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SCRIPT_RUNNING = 1;
    public static final int STATUS_SCRIPT_READY = 2;
    public static final int STATUS_GENERATING = 3;
    public static final int STATUS_COMPLETE = 4;
    public static final int STATUS_PARTIAL_FAILED = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Integer chapterNo;

    private String title;

    private String scriptText;

    private Integer status;

    private Integer pageCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
