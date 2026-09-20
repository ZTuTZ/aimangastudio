package com.aimanga.v2.repository;

import com.aimanga.v2.model.PageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface PageMapper extends BaseMapper<PageEntity> {

    @Update("UPDATE page SET layout_image_url = #{url}, layout_script_version = #{scriptVersion}, update_time = NOW() " +
            "WHERE id = #{pageId} AND script_version = #{scriptVersion}")
    int applyLayoutIfCurrent(@Param("pageId") Long pageId, @Param("scriptVersion") int scriptVersion,
                             @Param("url") String url);

    @Update("UPDATE page SET generated_image_url = #{url}, color_mode = #{colorMode}, generate_status = 2, " +
            "fail_reason = '', generate_records = #{records}, image_script_version = #{scriptVersion}, " +
            "image_revision = image_revision + 1, update_time = NOW() WHERE id = #{pageId} " +
            "AND script_version = #{scriptVersion} AND image_revision = #{imageRevision}")
    int applyImageIfCurrent(@Param("pageId") Long pageId, @Param("scriptVersion") int scriptVersion,
                            @Param("imageRevision") long imageRevision, @Param("url") String url,
                            @Param("colorMode") String colorMode, @Param("records") String records);

    @Update("UPDATE page SET generated_image_url = #{url}, generate_status = 2, fail_reason = '', " +
            "generate_records = #{records}, image_script_version = #{scriptVersion}, " +
            "image_revision = image_revision + 1, update_time = NOW() WHERE id = #{pageId} " +
            "AND script_version = #{scriptVersion} AND image_revision = #{imageRevision} " +
            "AND generated_image_url = #{sourceUrl}")
    int applyPostProcessIfCurrent(@Param("pageId") Long pageId, @Param("scriptVersion") int scriptVersion,
                                  @Param("imageRevision") long imageRevision,
                                  @Param("sourceUrl") String sourceUrl, @Param("url") String url,
                                  @Param("records") String records);
}
