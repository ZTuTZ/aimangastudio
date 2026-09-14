package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("page")
public class PageEntity {

    /** 0待生成 1生成中 2成功 3失败 */
    public static final int GEN_PENDING = 0;
    public static final int GEN_RUNNING = 1;
    public static final int GEN_SUCCESS = 2;
    public static final int GEN_FAILED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long chapterId;

    private Integer pageNo;

    /** 旁白(矩形框) */
    private String narration;

    /** 对白 JSON [{speaker,line}] */
    private String dialogue;

    /** 画面详述 */
    private String visual;

    /** 合成展示脚本 */
    private String sceneDescription;

    private String layoutImageUrl;

    private String generatedImageUrl;

    private String colorMode;

    private Integer generateStatus;

    private String failReason;

    /** 生成历史 JSON [{url,colorMode,kind,time}] */
    private String generateRecords;

    /** 脚本版本(T6.5.4):文本每次修改 +1;布局/成品记录生成时的版本,小于脚本版本 = 过期 */
    private Integer scriptVersion;

    private Integer layoutScriptVersion;

    private Integer imageScriptVersion;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
