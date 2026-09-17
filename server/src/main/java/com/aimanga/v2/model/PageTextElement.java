package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 页动态文本层元素(Phase 7.8):归一化坐标,底图与文本层解耦 */
@Data
@TableName("page_text_element")
public class PageTextElement {

    public static final String TYPE_DIALOGUE = "DIALOGUE";
    public static final String TYPE_NARRATION = "NARRATION";
    public static final String TYPE_THOUGHT = "THOUGHT";
    public static final String TYPE_SFX = "SFX";

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_AI = "AI";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long chapterId;

    private Long pageId;

    private String elementUid;

    /** DIALOGUE/NARRATION/THOUGHT/SFX */
    private String elementType;

    /** 对应 page.dialogue 数组索引,旁白为空 */
    private Integer dialogueIndex;

    private String speaker;

    private String textContent;

    /** 归一化坐标 0~1:左上角相对图片宽度 */
    private Double x;

    private Double y;

    private Double width;

    private Double height;

    /** 尾巴指向位置(归一化) */
    private Double tailX;

    private Double tailY;

    private String bubbleStyle;

    private String fontStyle;

    private Double fontSizeRatio;

    private String textAlign;

    private Integer maxLines;

    private Integer sortOrder;

    private String sourceType;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
