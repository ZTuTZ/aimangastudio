package com.aimanga.v2.repository;

import com.aimanga.v2.dto.TaskVO;
import com.aimanga.v2.model.TaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TaskMapper extends BaseMapper<TaskEntity> {

    /** 乐观锁领取:仅当仍为排队中时置为进行中,记录开始时间与执行锁 */
    @Update("UPDATE task SET status = 1, claim_token = #{token}, heartbeat_time = NOW(), start_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int claim(@Param("id") Long id, @Param("token") String token);

    /** Worker 心跳:仅持有执行锁的进行中任务可刷新 */
    @Update("UPDATE task SET heartbeat_time = NOW() WHERE id = #{id} AND claim_token = #{token} AND status = 1")
    int heartbeat(@Param("id") Long id, @Param("token") String token);

    /** 终态写入:带执行锁校验,防止看门狗/双 Worker 互踩 */
    @Update("UPDATE task SET status = #{status}, progress = #{progress}, error = #{error}, " +
            "last_error = #{error}, end_time = NOW(), claim_token = NULL, heartbeat_time = NULL " +
            "WHERE id = #{id} AND claim_token = #{token} AND status IN (1, 6)")
    int finishTask(@Param("id") Long id, @Param("token") String token, @Param("status") int status,
                   @Param("progress") int progress, @Param("error") String error);

    /** 看门狗:僵尸任务重新排队(自动重试次数 +1) */
    @Update("UPDATE task SET status = 0, retry_count = retry_count + 1, claim_token = NULL, " +
            "heartbeat_time = NULL, error = #{error}, last_error = #{error} " +
            "WHERE id = #{id} AND claim_token = #{token} AND status = 1")
    int requeueZombie(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    /** 看门狗:超过最大重试次数 → 失败 */
    @Update("UPDATE task SET status = 3, claim_token = NULL, heartbeat_time = NULL, end_time = NOW(), " +
            "error = #{error}, last_error = #{error} " +
            "WHERE id = #{id} AND claim_token = #{token} AND status = 1")
    int failZombie(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    /** 看门狗:扫描心跳超时的僵尸任务 */
    @Select("SELECT * FROM task WHERE status = 1 AND heartbeat_time IS NOT NULL " +
            "AND heartbeat_time < DATE_SUB(NOW(), INTERVAL timeout_seconds SECOND)")
    List<TaskEntity> selectZombies();

    /** Redis 补偿:扫描长时间排队但未被执行的任务(create_time 超过 60 秒仍在排队) */
    @Select("SELECT * FROM task WHERE status = 0 AND create_time < DATE_SUB(NOW(), INTERVAL 60 SECOND)")
    List<TaskEntity> selectStalePending();

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
                   t.retry_count AS retryCount, t.max_retry_count AS maxRetryCount,
                   LEFT(t.last_error, 200) AS lastError,
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
