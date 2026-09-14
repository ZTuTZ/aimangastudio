package com.aimanga.v2.repository;

import com.aimanga.v2.model.PipelineStageItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PipelineStageItemMapper extends BaseMapper<PipelineStageItem> {

    /**
     * Phase 5.9 Item 原子领取:PENDING(0) → RUNNING(1)。
     * 影响行数=1 领取成功;=0 说明已被其他 Worker 领取(或状态已变化),必须跳过。
     */
    @Update("UPDATE pipeline_stage_item SET status = 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int claim(@Param("id") Long id);

    /** 释放领取:RUNNING(1) → PENDING(0)。用于用户停止/暂停时归还在跑 Item,下次重跑继续。 */
    @Update("UPDATE pipeline_stage_item SET status = 0, update_time = NOW() " +
            "WHERE id = #{id} AND status = 1")
    int release(@Param("id") Long id);

    /** 按业务序号取一批 PENDING Item id(喂给 Worker 池的候选队列) */
    @Select("SELECT id FROM pipeline_stage_item " +
            "WHERE project_id = #{projectId} AND stage_type = #{stageType} AND status = 0 " +
            "ORDER BY business_id ASC LIMIT #{limit}")
    List<Long> selectPendingIds(@Param("projectId") Long projectId,
                                @Param("stageType") String stageType,
                                @Param("limit") int limit);

    /** 按状态分组计数(阶段进度统计用,避免全量行加载) */
    @Select("SELECT status, COUNT(*) AS cnt FROM pipeline_stage_item " +
            "WHERE project_id = #{projectId} AND stage_type = #{stageType} GROUP BY status")
    List<java.util.Map<String, Object>> selectStatusCounts(@Param("projectId") Long projectId,
                                                           @Param("stageType") String stageType);
}
