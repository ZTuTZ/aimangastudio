package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import com.aimanga.v2.model.PipelineStage;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.pipeline.PipelineStageService;
import com.aimanga.v2.repository.PipelineStageMapper;
import com.aimanga.v2.repository.ProjectMapper;
import com.aimanga.v2.service.TaskService;
import com.aimanga.v2.task.RedisSemaphores;
import com.aimanga.v2.task.TaskQueue;
import com.aimanga.v2.task.TaskStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局任务监控(Phase 7.2,仅 ADMIN,/api/admin/** 由 admin 过滤器把守):
 * - overview:运行/排队任务数、队列长度、AI 通道占用、全局并行配置;
 * - active-projects:有活跃任务的项目及其阶段级进度(布局 80/100 式聚合,不把 Stage Item 当独立任务展示)。
 */
@RestController
@RequestMapping("/api/admin/monitor")
@RequiredArgsConstructor
public class AdminMonitorController {

    private static final List<String> GEN_STAGES = List.of("SHEET", "REFERENCE", "LAYOUT", "IMAGE");

    private final TaskService taskService;
    private final TaskQueue taskQueue;
    private final RedisSemaphores redisSemaphores;
    private final com.aimanga.v2.service.ConfigService configService;
    private final ProjectMapper projectMapper;
    private final PipelineStageMapper stageMapper;

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runningTasks", taskService.countByStatus(TaskStatus.RUNNING));
        data.put("pendingTasks", taskService.countByStatus(TaskStatus.PENDING));
        data.put("queueLength", taskQueue.size());
        data.put("maxConcurrency", configService.getInt("task_max_concurrency", 5));
        data.put("imageConcurrency", configService.getInt("image_generation_concurrency", 5));
        List<Map<String, Object>> channels = new ArrayList<>();
        redisSemaphores.aiOccupancy().forEach((k, v) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("channel", k);
            c.put("used", v[0]);
            c.put("total", v[1]);
            channels.add(c);
        });
        data.put("aiChannels", channels);
        return Result.ok(data);
    }

    @GetMapping("/active-projects")
    public Result<List<Map<String, Object>>> activeProjects() {
        // 有活跃任务的项目
        List<TaskEntity> activeTasks = taskService.list(new LambdaQueryWrapper<TaskEntity>()
                .in(TaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.STOPPING)
                .orderByDesc(TaskEntity::getId));
        Map<Long, TaskEntity> latestByProject = new LinkedHashMap<>();
        for (TaskEntity t : activeTasks) {
            if (t.getProjectId() != null) {
                latestByProject.putIfAbsent(t.getProjectId(), t);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, TaskEntity> entry : latestByProject.entrySet()) {
            Long projectId = entry.getKey();
            TaskEntity task = entry.getValue();
            Project project = projectMapper.selectById(projectId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", projectId);
            row.put("projectTitle", project == null ? "" : project.getTitle());
            row.put("taskId", task.getId());
            row.put("taskType", task.getTaskType());
            row.put("taskStatus", task.getStatus());
            row.put("taskProgress", task.getProgress());
            // 阶段级聚合进度(素材/布局/成品;来自 comic_pipeline_stage,由 Runner 实时维护)
            List<PipelineStage> stages = stageMapper.selectList(new LambdaQueryWrapper<PipelineStage>()
                    .eq(PipelineStage::getProjectId, projectId)
                    .in(PipelineStage::getStageType, GEN_STAGES)
                    .orderByAsc(PipelineStage::getId));
            List<Map<String, Object>> stageRows = new ArrayList<>();
            for (PipelineStage stage : stages) {
                if (stage.getStatus() == null) continue;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("stageType", stage.getStageType());
                s.put("status", stage.getStatus());
                s.put("total", stage.getTotalCount());
                s.put("success", stage.getSuccessCount());
                s.put("failed", stage.getFailedCount());
                s.put("progress", stage.getProgress());
                stageRows.add(s);
            }
            row.put("stages", stageRows);
            result.add(row);
        }
        return Result.ok(result);
    }
}
