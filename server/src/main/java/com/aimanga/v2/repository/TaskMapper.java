package com.aimanga.v2.repository;

import com.aimanga.v2.dto.TaskVO;
import com.aimanga.v2.model.TaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TaskMapper extends BaseMapper<TaskEntity> {

    /** 乐观锁领取:仅当仍为排队中时置为进行中并记录开始时间 */
    @Update("UPDATE task SET status = 1, start_time = NOW() WHERE id = #{id} AND status = 0")
    int claim(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*)
            FROM task t
            LEFT JOIN project p ON t.project_id = p.id
            WHERE t.user_id = #{userId}
              AND (#{status} IS NULL OR t.status = #{status})
              AND (#{type} IS NULL OR t.task_type = #{type})
              AND (#{projectId} IS NULL OR t.project_id = #{projectId})
              AND (#{keyword} IS NULL OR p.title LIKE CONCAT('%', #{keyword}, '%'))
            """)
    long countFiltered(@Param("userId") Long userId,
                       @Param("status") Integer status,
                       @Param("type") String type,
                       @Param("projectId") Long projectId,
                       @Param("keyword") String keyword);

    @Select("""
            SELECT t.id, t.user_id AS userId, t.project_id AS projectId, t.chapter_id AS chapterId,
                   t.task_type AS taskType, t.status, t.priority, t.progress,
                   t.total_count AS totalCount, t.success_count AS successCount, t.fail_count AS failCount,
                   t.current_no AS currentNo, t.payload, t.error, t.result,
                   t.create_time AS createTime, t.start_time AS startTime, t.end_time AS endTime,
                   p.title AS projectTitle
            FROM task t
            LEFT JOIN project p ON t.project_id = p.id
            WHERE t.user_id = #{userId}
              AND (#{status} IS NULL OR t.status = #{status})
              AND (#{type} IS NULL OR t.task_type = #{type})
              AND (#{projectId} IS NULL OR t.project_id = #{projectId})
              AND (#{keyword} IS NULL OR p.title LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY t.id DESC
            LIMIT #{offset}, #{size}
            """)
    List<TaskVO> selectPageFiltered(@Param("userId") Long userId,
                                    @Param("status") Integer status,
                                    @Param("type") String type,
                                    @Param("projectId") Long projectId,
                                    @Param("keyword") String keyword,
                                    @Param("offset") long offset,
                                    @Param("size") int size);
}
