package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("style_preset")
public class StylePreset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String positivePrompt;

    private String negativePrompt;

    /** 建议色彩模式 partial/monochrome/color */
    private String colorMode;

    /** 风格参考图 JSON 数组 */
    private String refImages;

    private Integer sort;

    /** 1启用 0停用 */
    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
