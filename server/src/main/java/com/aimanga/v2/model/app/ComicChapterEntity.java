package com.aimanga.v2.model.app;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** [APP 模拟]话 */
@Data
@TableName("comic_chapter")
public class ComicChapterEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long comicId;

    private Integer chapterNo;

    private String title;
}
