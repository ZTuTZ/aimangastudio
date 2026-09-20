package com.aimanga.v2.repository;

import com.aimanga.v2.model.TaskPlanUnit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TaskPlanUnitMapper extends BaseMapper<TaskPlanUnit> {

    @Select("SELECT COUNT(*) FROM task_plan_unit u JOIN task t ON t.id = u.task_id " +
            "WHERE u.task_id <> #{taskId} AND t.project_id = #{projectId} AND t.status IN (0,1,6,7) " +
            "AND u.stage_type = #{stageType} AND u.business_type = #{businessType} " +
            "AND u.business_id = #{businessId} AND u.status IN (0,1)")
    int countActiveOwner(@Param("taskId") Long taskId, @Param("projectId") Long projectId,
                         @Param("stageType") String stageType, @Param("businessType") String businessType,
                         @Param("businessId") Long businessId);

    @Select("SELECT COUNT(*) FROM task_plan_unit u JOIN task t ON t.id = u.task_id " +
            "WHERE u.task_id <> #{taskId} AND t.status IN (0,1,6,7) " +
            "AND u.stage_type = 'EXPORT' AND u.business_type = 'PROJECT' " +
            "AND u.business_id = #{projectId} AND u.status IN (0,1)")
    int countActiveExportOwner(@Param("taskId") Long taskId, @Param("projectId") Long projectId);

    @Select("<script>SELECT COUNT(*) FROM task_plan_unit u JOIN task t ON t.id = u.task_id " +
            "WHERE u.task_id &lt;&gt; #{taskId} AND t.project_id = #{projectId} AND t.status IN (0,1,6,7) " +
            "AND u.business_type = 'PAGE' AND u.status IN (0,1) " +
            "AND u.business_id IN <foreach item='id' collection='pageIds' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int countActivePageOwners(@Param("taskId") Long taskId, @Param("projectId") Long projectId,
                              @Param("pageIds") List<Long> pageIds);

    @Update("<script>UPDATE task_plan_unit SET status = 4, attempt_token = NULL, error_message = #{reason}, " +
            "finish_time = NOW(), update_time = NOW() WHERE business_type = 'PAGE' AND business_id IN " +
            "<foreach item='id' collection='pageIds' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND status IN (0,2,3)</script>")
    int cancelPageUnits(@Param("pageIds") List<Long> pageIds, @Param("reason") String reason);

    @Select("SELECT * FROM task_plan_unit WHERE task_id = #{taskId} AND plan_version = #{planVersion} ORDER BY id")
    List<TaskPlanUnit> selectPlan(@Param("taskId") Long taskId, @Param("planVersion") int planVersion);

    @Select("SELECT business_id FROM task_plan_unit WHERE task_id = #{taskId} AND plan_version = #{planVersion} " +
            "AND stage_type = #{stageType} AND business_type = #{businessType} AND status IN (0,1,2,3) ORDER BY id")
    List<Long> selectBusinessIds(@Param("taskId") Long taskId, @Param("planVersion") int planVersion,
                                 @Param("stageType") String stageType, @Param("businessType") String businessType);

    @Update("UPDATE task_plan_unit SET status = 0, retry_count = 0, attempt_token = NULL, " +
            "error_message = '', finish_time = NULL WHERE task_id = #{taskId} AND plan_version = #{planVersion} AND status = 3")
    int reopenFailedForManualRetry(@Param("taskId") Long taskId, @Param("planVersion") int planVersion);

    @Select("SELECT COUNT(*) FROM task_plan_unit WHERE task_id = #{taskId} AND plan_version = #{planVersion} AND status = #{status}")
    int countByStatus(@Param("taskId") Long taskId, @Param("planVersion") int planVersion,
                      @Param("status") int status);

    /** 升级旧任务时复用已经成功的 Stage Item，不重复调用 AI。 */
    @Update("UPDATE task_plan_unit u JOIN task t ON t.id = u.task_id " +
            "JOIN pipeline_stage_item i ON i.project_id = t.project_id " +
            "AND i.stage_type = u.stage_type AND i.business_type = u.business_type " +
            "AND i.business_id = u.business_id " +
            "SET u.status = 2, u.result_ref = COALESCE(i.result_ref, '{}'), " +
            "u.error_message = '', u.finish_time = COALESCE(i.finish_time, NOW()), u.update_time = NOW() " +
            "WHERE u.task_id = #{taskId} AND u.plan_version = #{planVersion} " +
            "AND u.status = 0 AND i.status = 2")
    int inheritSuccessfulStageItems(@Param("taskId") Long taskId, @Param("planVersion") int planVersion);

    @Select("SELECT id FROM task_plan_unit WHERE task_id = #{taskId} AND plan_version = #{planVersion} " +
            "AND stage_type = #{stageType} AND business_type = #{businessType} AND business_id = #{businessId}")
    Long selectUnitId(@Param("taskId") Long taskId, @Param("planVersion") int planVersion,
                      @Param("stageType") String stageType, @Param("businessType") String businessType,
                      @Param("businessId") Long businessId);

    @Update("UPDATE task_plan_unit SET status = 1, attempt_token = #{attemptToken}, update_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int claim(@Param("id") Long id, @Param("attemptToken") String attemptToken);

    @Update("UPDATE task_plan_unit SET status = 0, attempt_token = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND status = 1 AND attempt_token = #{attemptToken}")
    int release(@Param("id") Long id, @Param("attemptToken") String attemptToken);

    @Update("UPDATE task_plan_unit SET status = 0, retry_count = retry_count + 1, attempt_token = NULL, " +
            "error_message = #{error}, update_time = NOW() WHERE id = #{id} AND status = 1 AND attempt_token = #{attemptToken}")
    int markRetry(@Param("id") Long id, @Param("attemptToken") String attemptToken, @Param("error") String error);

    @Update("UPDATE task_plan_unit SET status = 2, attempt_token = NULL, result_ref = #{resultRef}, " +
            "error_message = '', finish_time = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND status = 1 AND attempt_token = #{attemptToken}")
    int markSuccess(@Param("id") Long id, @Param("attemptToken") String attemptToken,
                    @Param("resultRef") String resultRef);

    @Update("UPDATE task_plan_unit SET status = 3, attempt_token = NULL, error_message = #{error}, " +
            "finish_time = NOW(), update_time = NOW() WHERE id = #{id} AND status = 1 AND attempt_token = #{attemptToken}")
    int markFailed(@Param("id") Long id, @Param("attemptToken") String attemptToken, @Param("error") String error);

    @Update("UPDATE task_plan_unit u JOIN task t ON t.id = u.task_id " +
            "SET u.status = 0, u.attempt_token = NULL, u.update_time = NOW() " +
            "WHERE u.status = 1 AND u.stage_type = #{stageType} AND t.project_id = #{projectId} AND " +
            "(t.status NOT IN (1,6) OR t.claim_token IS NULL OR " +
            "(t.lease_until IS NOT NULL AND t.lease_until <= NOW()))")
    int resetOrphanedRunning(@Param("projectId") Long projectId, @Param("stageType") String stageType);

    @Select("SELECT result_ref FROM task_plan_unit WHERE task_id = #{taskId} " +
            "AND stage_type = 'EXPORT' AND result_ref IS NOT NULL")
    List<String> selectExportCheckpointResults(@Param("taskId") Long taskId);
}
