package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("asset")
public class Asset {

    public static final int TYPE_CHARACTER = 1;
    public static final int TYPE_SCENE = 2;
    public static final int TYPE_PROP = 3;
    public static final int TYPE_OUTFIT = 4;

    /** 0空闲 1生成中 9失败 */
    public static final int GEN_IDLE = 0;
    public static final int GEN_RUNNING = 1;
    public static final int GEN_FAILED = 9;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** 1角色 2场景 3道具 4服装 */
    private Integer assetType;

    private String name;

    /** 别名 JSON 数组(同人合并) */
    private String aliases;

    private String description;

    /** 结构化设定 JSON(角色:{role,age,hair,accessories,top,bottom}) */
    private String structured;

    private String referenceUrl;

    private String sheetImageUrl;

    private Integer genStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
