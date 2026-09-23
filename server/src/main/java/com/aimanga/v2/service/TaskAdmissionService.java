package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.ProjectMapper;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.task.TaskEventPublisher;
import com.aimanga.v2.task.TaskQueue;
import com.aimanga.v2.task.TaskStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Single transactional admission boundary for every newly-created task.
 * Database project locks serialize the active-slot check, task insert and immutable plan initialization.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAdmissionService {

    private final ProjectMapper projectMapper;
    private final TaskMapper taskMapper;
    private final TaskPlanningService taskPlanningService;
    private final TaskQueue taskQueue;
    private final TaskEventPublisher publisher;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public record AdmissionCommand(Long userId, Long projectId, Long chapterId,
                                   String taskType, String payload) { }

    public record AdmissionResult(TaskEntity task, boolean created) { }

    @Transactional
    public AdmissionResult admit(AdmissionCommand command) {
        String payload = normalizePayload(command.payload());
        List<Project> lockedProjects = lockProjects(command.projectId(), command.taskType(), payload);
        Project anchor = lockedProjects.stream()
                .filter(project -> Objects.equals(project.getId(), command.projectId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "作品不存在: " + command.projectId()));

        TaskEntity active = findActive(command.projectId(), command.chapterId(), command.taskType(), payload, null);
        if (active != null) {
            if (Objects.equals(active.getPayload(), payload)) {
                return new AdmissionResult(active, false);
            }
            throw new BusinessException(409, "已有相同类型且范围重叠的任务正在执行或暂停;请先继续或停止该任务");
        }

        TaskEntity task = newTask(command, payload, anchor);
        taskMapper.insert(task);
        taskPlanningService.initializePlan(task);
        if (task.getStatus() == TaskStatus.PENDING) {
            afterCommit(() -> safeEnqueue(task.getId()));
        }
        afterCommit(() -> publisher.publishCreated(task));
        log.info("[task] 准入任务 type={} id={} projectId={}", task.getTaskType(), task.getId(), task.getProjectId());
        return new AdmissionResult(task, true);
    }

    /** Called from the retry transaction before locking the Task row. */
    public void lockProjectsFor(TaskEntity task) {
        lockProjects(task.getProjectId(), task.getTaskType(), normalizePayload(task.getPayload()));
    }

    /** Called after the retry target row is locked, while the project locks are still held. */
    public void assertRetrySlotAvailable(TaskEntity task) {
        TaskEntity active = findActive(task.getProjectId(), task.getChapterId(), task.getTaskType(),
                normalizePayload(task.getPayload()), task.getId());
        if (active != null) {
            throw new BusinessException(409, "已有相同类型且范围重叠的任务正在执行或暂停;不能重试当前任务");
        }
    }

    private List<Project> lockProjects(Long anchorProjectId, String taskType, String payload) {
        TreeSet<Long> ids = new TreeSet<>();
        if (anchorProjectId != null) ids.add(anchorProjectId);
        if ("EXPORT".equals(taskType)) ids.addAll(exportProjectIds(payload));
        if (ids.isEmpty()) throw new BusinessException(400, "任务缺少作品范围");
        List<Project> projects = projectMapper.lockByIds(new ArrayList<>(ids));
        if (projects.size() != ids.size()) {
            throw new BusinessException(404, "导出范围中存在已删除的作品");
        }
        return projects;
    }

    private TaskEntity findActive(Long projectId, Long chapterId, String type, String payload, Long excludeTaskId) {
        if ("EXPORT".equals(type)) {
            List<Long> projectIds = exportProjectIds(payload);
            if (projectIds.isEmpty()) projectIds = List.of(projectId);
            return taskMapper.selectActiveExportOverlapping(objectMapper.valueToTree(projectIds).toString(), excludeTaskId);
        }
        return taskMapper.selectOne(new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getProjectId, projectId)
                .eq(chapterId != null, TaskEntity::getChapterId, chapterId)
                .isNull(chapterId == null, TaskEntity::getChapterId)
                .eq(TaskEntity::getTaskType, type)
                .ne(excludeTaskId != null, TaskEntity::getId, excludeTaskId)
                .in(TaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING,
                        TaskStatus.STOPPING, TaskStatus.PAUSED)
                .orderByDesc(TaskEntity::getId)
                .last("LIMIT 1"));
    }

    private TaskEntity newTask(AdmissionCommand command, String payload, Project project) {
        TaskEntity task = new TaskEntity();
        task.setUserId(command.userId());
        task.setProjectId(command.projectId());
        task.setChapterId(command.chapterId());
        task.setTaskType(command.taskType());
        task.setStatus(TaskService.initialStatus(project));
        task.setPauseRequested(false);
        task.setControlVersion(0L);
        task.setPlanVersion(1);
        task.setPriority(0);
        task.setProgress(0);
        task.setTotalCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setCurrentNo(0);
        task.setMaxExecutionSeconds(Math.min(86_400, Math.max(60,
                configService.getInt("task_max_execution_seconds", 3600))));
        task.setPayload(payload);
        task.setError("");
        task.setCreateTime(LocalDateTime.now());
        return task;
    }

    private List<Long> exportProjectIds(String payload) {
        try {
            JsonNode values = objectMapper.readTree(payload).path("projectIds");
            if (!values.isArray()) return List.of();
            TreeSet<Long> ids = new TreeSet<>();
            values.forEach(value -> {
                if (value.canConvertToLong() && value.asLong() > 0) ids.add(value.asLong());
            });
            return List.copyOf(ids);
        } catch (Exception e) {
            throw new BusinessException(400, "任务 payload 不是有效 JSON");
        }
    }

    private static String normalizePayload(String payload) {
        return payload == null || payload.isBlank() ? "{}" : payload;
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void safeEnqueue(Long taskId) {
        try {
            taskQueue.enqueue(taskId);
        } catch (RuntimeException e) {
            log.warn("[task] Redis 入队失败，任务保留 PENDING 等待数据库补偿 taskId={}", taskId, e);
        }
    }
}
