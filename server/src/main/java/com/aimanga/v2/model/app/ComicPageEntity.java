package com.aimanga.v2.model.app;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** [APP 模拟]页 */
@Data
@TableName("comic_page")
public class ComicPageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long chapterId;

    private Integer pageNo;

    private String imageUrl;

    private String filePath;

    private String dialogue;

    private String narration;

    private String textLayer;

    private Integer scriptVersion;

    private Integer imageScriptVersion;

    private Integer textLayoutVersion;
}
