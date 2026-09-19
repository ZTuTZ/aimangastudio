package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.CreateTaskRequest;
import com.aimanga.v2.dto.PageResult;
import com.aimanga.v2.dto.TaskVO;
import com.aimanga.v2.model.TaskEntity;
import com.aimanga.v2.security.CurrentUser;
import com.aimanga.v2.service.TaskService;
import com.aimanga.v2.task.TaskSseHub;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskSseHub taskSseHub;

    @PostMapping
    public Result<TaskVO> create(@Valid @RequestBody CreateTaskRequest request) {
        return Result.ok(taskService.create(request));
    }

    @GetMapping
    public Result<PageResult<TaskVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String keyword) {
        if (page < 1) page = 1;
        if (size < 1 || size > 50) size = 12;
        return Result.ok(taskService.listPaged(page, size, status, type, projectId, keyword));
    }

    @GetMapping("/{id}")
    public Result<TaskVO> get(@PathVariable Long id) {
        TaskEntity task = taskService.requireAccessible(id);
        return Result.ok(taskService.toVO(task));
    }

    @PostMapping("/{id}/stop")
    public Result<TaskVO> stop(@PathVariable Long id) {
        return Result.ok(taskService.toVO(taskService.stop(id)));
    }

    @PostMapping("/{id}/pause")
    public Result<TaskVO> pause(@PathVariable Long id) {
        return Result.ok(taskService.toVO(taskService.pause(id)));
    }

    @PostMapping("/{id}/retry")
    public Result<TaskVO> retry(@PathVariable Long id) {
        return Result.ok(taskService.toVO(taskService.retry(id)));
    }

    @PostMapping("/{id}/resume")
    public Result<TaskVO> resume(@PathVariable Long id) {
        return Result.ok(taskService.toVO(taskService.resume(id)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return Result.ok();
    }

    /**
     * 任务事件 SSE 流(EventSource 用 ?token= 透传认证)。
     * scope=all(仅 ADMIN 生效)可订阅全局事件,供管理端任务监控使用。
     */
    @GetMapping("/events")
    public SseEmitter events(@RequestParam(defaultValue = "self") String scope) {
        return taskSseHub.register(CurrentUser.id(), "all".equals(scope));
    }
}
