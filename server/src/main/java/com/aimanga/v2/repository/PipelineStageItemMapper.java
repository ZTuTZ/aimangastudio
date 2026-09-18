package com.aimanga.v2.repository;

import com.aimanga.v2.model.PipelineStageItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PipelineStageItemMapper extends BaseMapper<PipelineStageItem> {

    /**
     * Phase 8.1 Attempt Fencing 原子领取:PENDING → RUNNING,同时写入 attempt 代次与 token。
     * 影响行数=1 领取成功;=0 已被其他 Worker 领取(或状态已变化)。
     */
    @Update("UPDATE pipeline_stage_item SET status = 1, attempt_no = attempt_no + 1, " +
            "attempt_token = #{attemptToken}, claimed_at = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int claim(@Param("id") Long id, @Param("attemptToken") String attemptToken);

    /**
     * Fenced 释放:RUNNING → PENDING。仅当前 token 持有者可释放;
     * 影响行数=0 说明 token 已失效(被新 Attempt 接管),调用方必须放弃本次执行结果。
     */
    @Update("UPDATE pipeline_stage_item SET status = 0, attempt_token = NULL, claimed_at = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND status = 1 AND attempt_token = #{attemptToken}")
    int release(@Param("id") Long id, @Param("attemptToken") String attemptToken);

    /** Fenced 重试:RUNNING → PENDING + retry_count+1(仅当前 token) */
    @Update("UPDATE pipeline_stage_item SET status = 0, error_message = #{error}, " +
            "retry_count = retry_count + 1, attempt_token = NULL, claimed_at = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND status = 1 AND attempt_token = #{attemptToken}")
    int markRetry(@Param("id") Long id, @Param("attemptToken") String attemptToken, @Param("error") String error);

    /** Fenced 终态失败:RUNNING → FAILED(仅当前 token) */
    @Update("UPDATE pipeline_stage_item SET status = 3, error_message = #{error}, finish_time = NOW(), " +
            "attempt_token = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND status = 1 AND attempt_token = #{attemptToken}")
    int markFailed(@Param("id") Long id, @Param("attemptToken") String attemptToken, @Param("error") String error);

    /** Fenced 成功:RUNNING → SUCCESS(仅当前 token) */
    @Update("UPDATE pipeline_stage_item SET status = 2, result_ref = #{resultRef}, finish_time = NOW(), " +
            "attempt_token = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND status = 1 AND attempt_token = #{attemptToken}")
    int markSuccess(@Param("id") Long id, @Param("attemptToken") String attemptToken,
                    @Param("resultRef") String resultRef);

    /** FOR UPDATE 行锁读取(fenced commit 事务内使用,Phase 8.1) */
    @Select("SELECT * FROM pipeline_stage_item WHERE id = #{id} FOR UPDATE")
    PipelineStageItem lockById(@Param("id") Long id);

    /** 按业务序号取一批 PENDING Item id(喂给 Worker 池的候选队列) */
    @Select("SELECT id FROM pipeline_stage_item " +
            "WHERE project_id = #{projectId} AND stage_type = #{stageType} AND status = 0 " +
            "ORDER BY business_id ASC LIMIT #{limit}")
    List<Long> selectPendingIds(@Param("projectId") Long projectId,
                                @Param("stageType") String stageType,
                                @Param("limit") int limit);

    /** Scope 化候选查询(Phase 8.2):只领取本次 Task 允许执行的 Item */
    @Select("<script>" +
            "SELECT id FROM pipeline_stage_item " +
            "WHERE project_id = #{projectId} AND stage_type = #{stageType} AND status = 0 " +
            "<if test='businessType != null'>AND business_type = #{businessType}</if> " +
            "<if test='businessIds != null and businessIds.size() > 0'>" +
            "AND business_id IN <foreach item='i' collection='businessIds' open='(' separator=',' close=')'>#{i}</foreach>" +
            "</if> " +
            "ORDER BY business_id ASC LIMIT #{limit}" +
            "</script>")
    List<Long> selectPendingIdsInScope(@Param("projectId") Long projectId,
                                       @Param("stageType") String stageType,
                                       @Param("businessType") String businessType,
                                       @Param("businessIds") java.util.Collection<Long> businessIds,
                                       @Param("limit") int limit);

    /** 按状态分组计数(阶段进度统计用,避免全量行加载) */
    @Select("SELECT status, COUNT(*) AS cnt FROM pipeline_stage_item " +
            "WHERE project_id = #{projectId} AND stage_type = #{stageType} GROUP BY status")
    List<java.util.Map<String, Object>> selectStatusCounts(@Param("projectId") Long projectId,
                                                           @Param("stageType") String stageType);

    /** 页级归属校验:该 item 是否属于指定页(fenced commit 用) */
    @Select("SELECT id FROM pipeline_stage_item " +
            "WHERE id = #{id} AND attempt_token = #{attemptToken} AND status = 1")
    Long findRunningWithToken(@Param("id") Long id, @Param("attemptToken") String attemptToken);
}
