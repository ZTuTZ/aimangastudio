package com.aimanga.v2.task;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.repository.TaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务执行器:领取(乐观锁防重复) → 分发处理器 → 落终态(成功/失败/部分失败/停止) → 发布事件。
 * 所有状态转换都以 MySQL 为准。
 */
@Slf4j
@Component
public class TaskRunner {

    private final TaskMapper taskMapper;
    private final TaskEventPublisher publisher;
    private final Map<String, TaskHandler> handlers;

    public TaskRunner(TaskMapper taskMapper, TaskEventPublisher publisher, List<TaskHandler> handlerList) {
        this.taskMapper = taskMapper;
        this.publisher = publisher;
        this.handlers = handlerList.stream().collect(Collectors.toMap(TaskHandler::type, Function.identity()));
        log.info("[task] 已注册任务处理器: {}", handlers.keySet());
    }

    public void run(long taskId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        if (taskMapper.claim(taskId) == 0) {
            return; // 已被其他 worker 领取或已被停止
        }
        TaskEntity running = taskMapper.selectById(taskId);
        running.setStartTime(LocalDateTime.now());
        publisher.publishStatus(running, TaskStatus.RUNNING, "任务开始");
        TaskRuntime runtime = new TaskRuntime(taskMapper, publisher, running);
        try {
            TaskHandler handler = handlers.get(running.getTaskType());
            if (handler == null) {
                throw new BusinessException(500, "该任务类型暂未实现: " + running.getTaskType());
            }
            handler.run(running, runtime);
            int fail = runtime.failCount();
            int success = runtime.successCount();
            if (fail > 0 && success > 0) {
                finish(running, runtime, TaskStatus.PARTIAL, null);
            } else if (fail > 0) {
                finish(running, runtime, TaskStatus.FAILED, "全部步骤失败");
            } else {
                finish(running, runtime, TaskStatus.SUCCESS, null);
            }
        } catch (TaskStopSignal s) {
            log.info("[task] 任务被用户停止 taskId={}", taskId);
            finish(running, runtime, TaskStatus.STOPPED, "已停止");
        } catch (BusinessException e) {
            log.warn("[task] 任务失败 taskId={}: {}", taskId, e.getMessage(), e);
            finish(running, runtime, TaskStatus.FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("[task] 任务异常 taskId={}", taskId, e);
            finish(running, runtime, TaskStatus.FAILED, truncate(e.getMessage()));
        }
    }

    private void finish(TaskEntity task, TaskRuntime runtime, int status, String error) {
        TaskEntity patch = new TaskEntity();
        patch.setId(task.getId());
        patch.setStatus(status);
        patch.setProgress(status == TaskStatus.SUCCESS || status == TaskStatus.PARTIAL ? 100 : runtime.progress());
        patch.setEndTime(LocalDateTime.now());
        patch.setError(error == null ? "" : error);
        taskMapper.updateById(patch);
        publisher.publishStatus(task, status, error == null ? "" : error);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
