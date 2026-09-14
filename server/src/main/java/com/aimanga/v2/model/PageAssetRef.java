package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 页-资产素材绑定(Phase 6.1):每页明确引用哪些角色/场景/道具/服装 */
@Data
@TableName("page_asset_ref")
public class PageAssetRef {

    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_MATCH = "MATCH";
    public static final String SOURCE_MANUAL = "MANUAL";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long pageId;

    private Long assetId;

    /** 1必需(角色) 0可选(场景/道具/服装) */
    private Integer requiredFlag;

    /** AI/MATCH/MANUAL */
    private String source;

    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
