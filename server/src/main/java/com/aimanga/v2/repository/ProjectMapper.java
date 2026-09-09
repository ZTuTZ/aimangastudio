package com.aimanga.v2.repository;

import com.aimanga.v2.dto.ProjectVO;
import com.aimanga.v2.model.Project;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProjectMapper extends BaseMapper<Project> {

    /**
     * 分页计数(不触达 LONGTEXT,不关联子查询,走 idx_project_user 索引)。
     * keyword 已在服务层做 LIKE 转义。
     */
    @Select("""
            SELECT COUNT(*)
            FROM project
            WHERE user_id = #{userId}
              AND (#{status} IS NULL OR status = #{status})
              AND (#{keyword} IS NULL OR title LIKE CONCAT('%', #{keyword}, '%'))
            """)
    long countByUserFiltered(@Param("userId") Long userId,
                             @Param("status") Integer status,
                             @Param("keyword") String keyword);

    /**
     * 分页列表:
     * - 明确列清单,绝不 SELECT source_text(LONGTEXT);
     * - 话数/页数为相关子查询,均命中 chapter 唯一键前缀与 idx_page_project 索引,
     *   单行成本恒定,每页至多 size 行,单次往返,无 N+1;
     * - 深分页时 LIMIT offset,size 由索引覆盖排序(id 主键),无需 filesort。
     */
    @Select("""
            SELECT p.id, p.title, p.status,
                   p.content_uid AS contentUid,
                   p.aspect_ratio AS aspectRatio, p.color_mode AS colorMode,
                   p.style_preset_id AS stylePresetId, p.tagline,
                   p.cover_url AS coverUrl, p.category, p.tags, p.series_status AS seriesStatus,
                   p.create_time AS createTime, p.update_time AS updateTime,
                   (SELECT COUNT(*) FROM chapter c WHERE c.project_id = p.id) AS chapterCount,
                   (SELECT COUNT(*) FROM page g WHERE g.project_id = p.id) AS pageCount
            FROM project p
            WHERE p.user_id = #{userId}
              AND (#{status} IS NULL OR p.status = #{status})
              AND (#{keyword} IS NULL OR p.title LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY p.id DESC
            LIMIT #{offset}, #{size}
            """)
    List<ProjectVO> selectPageByUserFiltered(@Param("userId") Long userId,
                                             @Param("status") Integer status,
                                             @Param("keyword") String keyword,
                                             @Param("offset") long offset,
                                             @Param("size") int size);
}
