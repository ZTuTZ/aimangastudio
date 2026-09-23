package com.aimanga.v2.repository;

import com.aimanga.v2.dto.TaskVO;
import com.aimanga.v2.model.TaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TaskMapper extends BaseMapper<TaskEntity> {

    @Select("SELECT * FROM task WHERE id = #{id} FOR UPDATE")
    TaskEntity lockById(@Param("id") Long id);

    @Select("SELECT * FROM task WHERE task_type = 'EXPORT' " +
            "AND status IN (0,1,6,7) AND (#{excludeTaskId} IS NULL OR id != #{excludeTaskId}) " +
            "AND JSON_OVERLAPS(JSON_EXTRACT(payload, '$.projectIds'), CAST(#{projectIdsJson} AS JSON)) " +
            "ORDER BY id DESC LIMIT 1")
    TaskEntity selectActiveExportOverlapping(@Param("projectIdsJson") String projectIdsJson,
                                             @Param("excludeTaskId") Long excludeTaskId);

    /** 当前执行所有者是否受到任务级或项目级暂停意图约束。 */
    @Select("SELECT EXISTS(SELECT 1 FROM task t LEFT JOIN project p ON p.id = t.project_id " +
            "WHERE t.id = #{id} AND t.claim_token = #{claimToken} AND t.status IN (1, 6) " +
            "AND (t.pause_requested = 1 OR COALESCE(p.pause_requested, 0) = 1))")
    boolean isEffectivePauseRequested(@Param("id") Long id, @Param("claimToken") String claimToken);

    /**
     * Fenced 业务提交前锁定仍由本 Worker 持有的 Task。
     * STOPPING 仍允许已领取 Item 收尾；PAUSED/终态/被接管的 Task 一律拒绝。
     */
    @Select("SELECT * FROM task WHERE id = #{id} AND claim_token = #{token} " +
            "AND status IN (1, 6) AND (lease_until IS NULL OR lease_until >= NOW()) FOR UPDATE")
    TaskEntity lockActiveClaim(@Param("id") Long id, @Param("token") String token);

    /** 乐观锁领取(Phase 8.4):记录执行实例 + 执行租约(lease_until=NOW()+task_lease_seconds) */
    @Update("UPDATE task SET status = 1, claim_token = #{token}, heartbeat_time = NOW(), start_time = NOW(), " +
            "worker_instance_id = #{instanceId}, " +
            "lease_until = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND) " +
            "WHERE id = #{id} AND status = 0 AND pause_requested = 0 " +
            "AND plan_initialized_at IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM project p WHERE p.id = task.project_id AND p.pause_requested = 1)")
    int claim(@Param("id") Long id, @Param("token") String token,
              @Param("instanceId") String instanceId, @Param("leaseSeconds") int leaseSeconds);

    /** Worker 心跳(Phase 8.4 CAS):刷新心跳并续租;仅当前 claim_token 持有者 */
    @Update("UPDATE task SET heartbeat_time = NOW(), " +
            "lease_until = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND) " +
            "WHERE id = #{id} AND claim_token = #{token} AND status IN (1, 6)")
    int heartbeat(@Param("id") Long id, @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);

    /** 运行时进度写入必须仍由当前租约持有者提交，避免已被回收的旧 Worker 覆盖新 Attempt。 */
    @Update("UPDATE task SET total_count = #{total}, success_count = #{success}, fail_count = #{fail}, " +
            "processed_count = #{processed}, progress = #{progress} " +
            "WHERE id = #{id} AND claim_token = #{token} AND status IN (1, 6)")
    int updateProgress(@Param("id") Long id, @Param("token") String token,
                       @Param("total") int total, @Param("success") int success,
                       @Param("fail") int fail, @Param("processed") int processed,
                       @Param("progress") int progress);

    /** 终态/暂停写入后清租约 */
    @Update("UPDATE task SET lease_until = NULL, worker_instance_id = NULL WHERE id = #{id} " +
            "AND claim_token IS NULL")
    int clearLease(@Param("id") Long id);

    /** Phase 8.4 双超时扫描:租约过期(Worker失联) 或 执行超时(有心跳但运行过久) */
    @Select("SELECT * FROM task WHERE status = 1 AND (" +
            "lease_until IS NOT NULL AND lease_until < NOW() OR " +
            "max_execution_seconds IS NOT NULL AND start_time IS NOT NULL " +
            "AND start_time < DATE_SUB(NOW(), INTERVAL max_execution_seconds SECOND))")
    List<TaskEntity> selectLeaseOrExecutionTimeout();

    /** STOPPING 僵尸(Phase 8.4 §6.6):用户停止意图不可丢失,Worker 已死 → 直接 STOPPED */
    @Select("SELECT * FROM task WHERE status = 6 AND claim_token IS NOT NULL AND (" +
            "lease_until <= NOW() OR (lease_until IS NULL AND heartbeat_time < DATE_SUB(NOW(), INTERVAL 90 SECOND)))")
    List<TaskEntity> selectStoppingZombies();

    @Update("UPDATE task SET status = 5, error = '已停止(Worker 失联)', end_time = NOW(), " +
            "claim_token = NULL, heartbeat_time = NULL, lease_until = NULL WHERE id = #{id} " +
            "AND status = 6 AND claim_token = #{token}")
    int stopZombie(@Param("id") Long id, @Param("token") String token);

    /** 终态写入:带执行锁校验,防止看门狗/双 Worker 互踩 */
    @Update("UPDATE task SET status = CASE WHEN status = 6 THEN 5 ELSE #{status} END, " +
            "progress = #{progress}, error = CASE WHEN status = 6 THEN '已停止' ELSE #{error} END, " +
            "last_error = CASE WHEN status = 6 THEN '已停止' ELSE #{error} END, end_time = NOW(), claim_token = NULL, heartbeat_time = NULL, " +
            "lease_until = NULL, worker_instance_id = NULL " +
            "WHERE id = #{id} AND claim_token = #{token} AND status IN (1, 6)")
    int finishTask(@Param("id") Long id, @Param("token") String token, @Param("status") int status,
                   @Param("progress") int progress, @Param("error") String error);

    /** 看门狗:僵尸任务重新排队(自动重试次数 +1) */
    @Update("UPDATE task SET status = 0, retry_count = retry_count + 1, claim_token = NULL, " +
            "heartbeat_time = NULL, error = #{error}, last_error = #{error} " +
            "WHERE id = #{id} AND claim_token = #{token} AND status = 1 AND (" +
            "(lease_until IS NOT NULL AND lease_until < NOW()) OR " +
            "(max_execution_seconds IS NOT NULL AND start_time IS NOT NULL " +
            "AND start_time < DATE_SUB(NOW(), INTERVAL max_execution_seconds SECOND)))")
    int requeueZombie(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    /** 看门狗:超过最大重试次数 → 失败 */
    @Update("UPDATE task SET status = 3, claim_token = NULL, heartbeat_time = NULL, end_time = NOW(), " +
            "error = #{error}, last_error = #{error} " +
            "WHERE id = #{id} AND claim_token = #{token} AND status = 1 AND (" +
            "(lease_until IS NOT NULL AND lease_until < NOW()) OR " +
            "(max_execution_seconds IS NOT NULL AND start_time IS NOT NULL " +
            "AND start_time < DATE_SUB(NOW(), INTERVAL max_execution_seconds SECOND)))")
    int failZombie(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    /** 用户停止意图:仅 RUNNING 可进入 STOPPING，避免覆盖已完成的终态。 */
    @Update("UPDATE task SET status = 6 WHERE id = #{id} AND status = 1")
    int requestStopRunning(@Param("id") Long id);

    /** 排队/暂停任务无需 drain，CAS 直接停止。 */
    @Update("UPDATE task SET status = 5, error = '已停止', end_time = NOW(), claim_token = NULL, " +
            "heartbeat_time = NULL, lease_until = NULL, worker_instance_id = NULL " +
            "WHERE id = #{id} AND status IN (0, 7)")
    int stopPendingOrPaused(@Param("id") Long id);

    @Update("UPDATE task SET pause_requested = 1, control_version = control_version + 1 WHERE id = #{id} AND status = 1")
    int requestPauseRunning(@Param("id") Long id);

    @Update("UPDATE task SET status = 7, pause_requested = 1, control_version = control_version + 1 WHERE id = #{id} AND status = 0")
    int pausePending(@Param("id") Long id);

    @Update("UPDATE task SET status = 7 WHERE project_id = #{projectId} AND status = 0")
    int pausePendingForProject(@Param("projectId") Long projectId);

    @Select("SELECT id FROM task WHERE project_id = #{projectId} AND status = 7 AND pause_requested = 0 ORDER BY id")
    List<Long> selectProjectPausedTaskIds(@Param("projectId") Long projectId);

    @Update("UPDATE task SET status = 0, control_version = control_version + 1 " +
            "WHERE id = #{id} AND status = 7 AND pause_requested = 0")
    int resumeProjectPausedTask(@Param("id") Long id);

    @Update("UPDATE task SET pause_requested = 0, control_version = control_version + 1 " +
            "WHERE id = #{id} AND status = 1 AND pause_requested = 1 " +
            "AND NOT EXISTS (SELECT 1 FROM project p WHERE p.id = task.project_id AND p.pause_requested = 1)")
    int cancelPauseRunning(@Param("id") Long id);

    /** 看门狗:扫描心跳超时的僵尸任务 */
    @Select("SELECT * FROM task WHERE status = 1 AND heartbeat_time IS NOT NULL " +
            "AND heartbeat_time < DATE_SUB(NOW(), INTERVAL timeout_seconds SECOND)")
    List<TaskEntity> selectZombies();

    /** 租约/执行超时的回收(Phase 8.4):以 claim_token 为围栏,不看心跳时间 */
    @Update("UPDATE task SET status = 0, retry_count = retry_count + 1, claim_token = NULL, heartbeat_time = NULL, " +
            "lease_until = NULL, worker_instance_id = NULL, error = #{error}, last_error = #{error} " +
            "WHERE id = #{id} AND claim_token = #{token} AND status = 1 AND (" +
            "(lease_until IS NOT NULL AND lease_until < NOW()) OR " +
            "(max_execution_seconds IS NOT NULL AND start_time IS NOT NULL " +
            "AND start_time < DATE_SUB(NOW(), INTERVAL max_execution_seconds SECOND)))")
    int requeueRevoked(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    /** 用户并发许可已失效：立即撤销数据库所有权，旧 Worker 的后续业务提交将被 fencing 拒绝。 */
    @Update("UPDATE task SET status = 0, claim_token = NULL, heartbeat_time = NULL, lease_until = NULL, " +
            "worker_instance_id = NULL, error = #{error}, last_error = #{error} " +
            "WHERE id = #{id} AND claim_token = #{token} AND status = 1")
    int requeueAfterPermitLoss(@Param("id") Long id, @Param("token") String token,
                               @Param("error") String error);

    @Update("UPDATE task SET status = 3, claim_token = NULL, heartbeat_time = NULL, end_time = NOW(), " +
            "lease_until = NULL, worker_instance_id = NULL, error = #{error}, last_error = #{error} " +
            "WHERE id = #{id} AND claim_token = #{token} AND status = 1 AND (" +
            "(lease_until IS NOT NULL AND lease_until < NOW()) OR " +
            "(max_execution_seconds IS NOT NULL AND start_time IS NOT NULL " +
            "AND start_time < DATE_SUB(NOW(), INTERVAL max_execution_seconds SECOND)))")
    int failRevoked(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    /** Redis 补偿:扫描长时间排队但未被执行的任务(create_time 超过 60 秒仍在排队) */
    @Select("SELECT * FROM task WHERE status = 0 AND plan_initialized_at IS NOT NULL " +
            "AND create_time < DATE_SUB(NOW(), INTERVAL 60 SECOND)")
    List<TaskEntity> selectStalePending();

    /** 升级兼容:旧 PENDING/PAUSED 任务必须先固化原 payload 对应的执行计划，Worker 才能领取。 */
    @Select("SELECT * FROM task WHERE status IN (0,7) AND plan_initialized_at IS NULL ORDER BY id LIMIT 100")
    List<TaskEntity> selectUnplannedPendingOrPaused();

    @Update("UPDATE task SET status = 3, error = #{error}, last_error = #{error}, end_time = NOW() " +
            "WHERE id = #{id} AND status IN (0,7) AND plan_initialized_at IS NULL")
    int failUnplanned(@Param("id") Long id, @Param("error") String error);

    /** 暂停任务(Phase 8.3):RUNNING → PAUSED,保留 payload/进度/计数,清执行锁与心跳 */
    @Update("UPDATE task SET status = CASE WHEN pause_requested = 1 OR EXISTS (" +
            "SELECT 1 FROM project p WHERE p.id = task.project_id AND p.pause_requested = 1" +
            ") THEN 7 ELSE 0 END, claim_token = NULL, heartbeat_time = NULL, " +
            "lease_until = NULL, worker_instance_id = NULL " +
            "WHERE id = #{id} AND claim_token = #{claimToken} AND status = 1")
    int pauseTask(@Param("id") Long id, @Param("claimToken") String claimToken);

    /** 恢复暂停任务(Phase 8.3):PAUSED → PENDING,payload/进度/计数原样保留 */
    @Update("UPDATE task SET status = 0, pause_requested = 0, control_version = control_version + 1, error = '', claim_token = NULL, heartbeat_time = NULL " +
            "WHERE id = #{id} AND status = 7 " +
            "AND NOT EXISTS (SELECT 1 FROM project p WHERE p.id = task.project_id AND p.pause_requested = 1)")
    int resumeTask(@Param("id") Long id);

    @Update("UPDATE task SET result = #{result} WHERE id = #{id} AND claim_token = #{claimToken} AND status IN (1,6)")
    int updateResultOwned(@Param("id") Long id, @Param("claimToken") String claimToken,
                          @Param("result") String result);

    @Select("""
            SELECT COUNT(*)
            FROM task t
            LEFT JOIN project p ON t.project_id = p.id
            WHERE (#{userId} IS NULL OR t.user_id = #{userId})
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
                   t.pause_requested AS pauseRequested,
                   p.title AS projectTitle
            FROM task t
            LEFT JOIN project p ON t.project_id = p.id
            WHERE (#{userId} IS NULL OR t.user_id = #{userId})
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
