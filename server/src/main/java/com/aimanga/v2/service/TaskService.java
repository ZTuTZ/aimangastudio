package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.dto.CreateTaskRequest;
import com.aimanga.v2.dto.PageResult;
import com.aimanga.v2.dto.TaskVO;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import com.aimanga.v2.security.CurrentUser;
import com.aimanga.v2.task.TaskEventPublisher;
import com.aimanga.v2.task.TaskQueue;
import com.aimanga.v2.task.TaskStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 任务服务:创建(入队)/分页查询/详情/停止/重试/删除。
 * MySQL task 表是唯一事实;Redis 只承担队列与事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService extends ServiceImpl<TaskMapper, TaskEntity> {

    /** MOCK 为系统测试类型:验证任务系统本身;真实业务类型处理器在 Phase 5/6 提供 */
    public static final Set<String> TYPES = Set.of(
            "SPLIT", "SCRIPT", "ASSET", "SHEET", "BATCH", "PAGE", "LAYOUT", "COLORIZE", "CLEAN", "REPAINT", "MOCK");

    private static final Set<String> NEEDS_PAGE = Set.of("PAGE", "LAYOUT", "COLORIZE", "CLEAN", "REPAINT");

    private final ProjectService projectService;
    private final PageService pageService;
    private final TaskQueue taskQueue;
    private final TaskEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final com.aimanga.v2.repository.ProjectMapper projectMapper;
    private final RedissonClient redissonClient;

    public TaskVO create(CreateTaskRequest request) {
        String type = request.taskType() == null ? "" : request.taskType().trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new BusinessException(400, "不支持的任务类型: " + type + ",可选: " + String.join("/", TYPES));
        }
        Project project = projectService.requireAccessible(request.projectId());
        if (NEEDS_PAGE.contains(type)) {
            Long pageId = extractPageId(request.payload());
            if (pageId == null) {
                throw new BusinessException(400, type + " 任务需要在 payload 中提供 pageId");
            }
            pageService.requireAccessible(pageId);
        }
        TaskEntity task = new TaskEntity();
        task.setUserId(CurrentUser.id());
        task.setProjectId(project.getId());
        task.setChapterId(request.chapterId());
        task.setTaskType(type);
        task.setStatus(TaskStatus.PENDING);
        task.setPriority(0);
        task.setProgress(0);
        task.setTotalCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setCurrentNo(0);
        task.setPayload(request.payload() == null ? "{}" : request.payload().toString());
        task.setError("");
        task.setCreateTime(LocalDateTime.now());
        save(task);
        taskQueue.enqueue(task.getId());
        publisher.publishCreated(task);
        log.info("[task] 创建任务 type={} id={} projectId={}", type, task.getId(), project.getId());
        return toVO(task, project.getTitle());
    }

    public PageResult<TaskVO> listPaged(int page, int size, Integer status, String type, Long projectId, String keyword) {
        Long userId = CurrentUser.id();
        String t = (type == null || type.isBlank()) ? null : type.trim().toUpperCase();
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        long total = baseMapper.countFiltered(userId, status, t, projectId, kw);
        List<TaskVO> records = total == 0
                ? List.of()
                : baseMapper.selectPageFiltered(userId, status, t, projectId, kw, (long) (page - 1) * size, size);
        return new PageResult<>(records, total);
    }

    public TaskEntity requireAccessible(Long taskId) {
        TaskEntity task = getById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        if (!CurrentUser.isAdmin() && !task.getUserId().equals(CurrentUser.id())) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        return task;
    }

    /** 停止:排队中直接置已停止;进行中置"停止中",由处理器在下一个步骤前感知并落终态 */
    public TaskEntity stop(Long taskId) {
        TaskEntity task = requireAccessible(taskId);
        int status = task.getStatus() == null ? TaskStatus.PENDING : task.getStatus();
        if (status == TaskStatus.PENDING) {
            TaskEntity patch = new TaskEntity();
            patch.setId(taskId);
            patch.setStatus(TaskStatus.STOPPED);
            patch.setError("已停止");
            patch.setEndTime(LocalDateTime.now());
            updateById(patch);
        } else if (status == TaskStatus.RUNNING) {
            TaskEntity patch = new TaskEntity();
            patch.setId(taskId);
            patch.setStatus(TaskStatus.STOPPING);
            updateById(patch);
        } else {
            throw new BusinessException(409, "任务已结束,无需停止");
        }
        TaskEntity latest = getById(taskId);
        publisher.publishStatus(latest, latest.getStatus(), latest.getError());
        return latest;
    }

    /** 重试:终态任务重置为排队中并重新入队(保留断点 current_no) */
    public TaskEntity retry(Long taskId) {
        TaskEntity task = requireAccessible(taskId);
        int status = task.getStatus() == null ? TaskStatus.PENDING : task.getStatus();
        if (TaskStatus.active(status)) {
            throw new BusinessException(409, "任务进行中,不能重试");
        }
        TaskEntity patch = new TaskEntity();
        patch.setId(taskId);
        patch.setStatus(TaskStatus.PENDING);
        patch.setProgress(0);
        patch.setSuccessCount(0);
        patch.setFailCount(0);
        patch.setTotalCount(0);
        patch.setError("");
        patch.setEndTime(null);
        patch.setStartTime(null);
        updateById(patch);
        taskQueue.enqueue(taskId);
        TaskEntity latest = getById(taskId);
        publisher.publishStatus(latest, TaskStatus.PENDING, "已重新入队");
        return latest;
    }

    public void delete(Long taskId) {
        TaskEntity task = requireAccessible(taskId);
        int status = task.getStatus() == null ? TaskStatus.PENDING : task.getStatus();
        if (TaskStatus.active(status)) {
            throw new BusinessException(409, "任务进行中,请先停止后再删除");
        }
        removeById(taskId);
    }

    public TaskVO toVO(TaskEntity task, String projectTitle) {
        TaskVO vo = new TaskVO();
        vo.setId(task.getId());
        vo.setUserId(task.getUserId());
        vo.setProjectId(task.getProjectId());
        vo.setChapterId(task.getChapterId());
        vo.setTaskType(task.getTaskType());
        vo.setStatus(task.getStatus());
        vo.setPriority(task.getPriority());
        vo.setProgress(task.getProgress());
        vo.setTotalCount(task.getTotalCount());
        vo.setSuccessCount(task.getSuccessCount());
        vo.setFailCount(task.getFailCount());
        vo.setCurrentNo(task.getCurrentNo());
        vo.setPayload(task.getPayload());
        vo.setError(task.getError());
        vo.setResult(task.getResult());
        vo.setCreateTime(task.getCreateTime());
        vo.setStartTime(task.getStartTime());
        vo.setEndTime(task.getEndTime());
        vo.setProjectTitle(projectTitle);
        return vo;
    }

    public TaskVO toVO(TaskEntity task) {
        return toVO(task, null);
    }

    /**
     * 系统内部入队(流水线链式调用,worker 线程无 Shiro 上下文):
     * userId 取作品归属,不做用户归属校验。
     */
    public TaskEntity createSystemTask(Long projectId, Long chapterId, String type, String payloadJson) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "作品不存在: " + projectId);
        }
        TaskEntity task = new TaskEntity();
        task.setUserId(project.getUserId());
        task.setProjectId(projectId);
        task.setChapterId(chapterId);
        task.setTaskType(type);
        task.setStatus(TaskStatus.PENDING);
        task.setPriority(0);
        task.setProgress(0);
        task.setTotalCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setCurrentNo(0);
        task.setPayload(payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson);
        task.setError("");
        task.setCreateTime(LocalDateTime.now());
        save(task);
        taskQueue.enqueue(task.getId());
        publisher.publishCreated(task);
        return task;
    }

    /**
     * 链式任务去重入队:同一 (projectId, chapterId, type) 在 PENDING/RUNNING 时只允许存在一个,
     * 防止多个 SCRIPT 并发结束时重复入队 SHEET/ASSET。Redisson 短锁内查询 + 创建。
     */
    public boolean enqueueUnique(Long projectId, Long chapterId, String type, String payloadJson) {
        String lockKey = "aimanga:v2:enqueue:" + projectId + ":" + (chapterId == null ? 0 : chapterId) + ":" + type;
        org.redisson.api.RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                return false; // 另一个线程正在入队同一链任务
            }
            try {
                Long active = baseMapper.selectCount(new LambdaQueryWrapper<TaskEntity>()
                        .eq(TaskEntity::getProjectId, projectId)
                        .eq(chapterId != null, TaskEntity::getChapterId, chapterId)
                        .isNull(chapterId == null, TaskEntity::getChapterId)
                        .eq(TaskEntity::getTaskType, type)
                        .in(TaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.STOPPING));
                if (active != null && active > 0) {
                    return false;
                }
                createSystemTask(projectId, chapterId, type, payloadJson);
                return true;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Long extractPageId(com.fasterxml.jackson.databind.JsonNode payload) {
        if (payload == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode pageId = payload.get("pageId");
        return pageId != null && pageId.canConvertToLong() ? pageId.asLong() : null;
    }
}
