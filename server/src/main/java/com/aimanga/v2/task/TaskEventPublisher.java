package com.aimanga.v2.task;

import com.aimanga.v2.model.TaskEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 任务事件发布:Redis Pub/Sub 频道 aimanga:v2:events + 进度缓存(Redis Hash,可观测/可恢复)。
 * SSE 网关(TaskSseHub)订阅该频道并按 userId 过滤推送给浏览器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEventPublisher {

    public static final String CHANNEL = "aimanga:v2:events";

    public static final String EVENT_CREATED = "task.created";
    public static final String EVENT_STATUS = "task.status";
    public static final String EVENT_PROGRESS = "task.progress";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /** 发布任务创建 */
    public void publishCreated(TaskEntity task) {
        publish(EVENT_CREATED, task, null);
    }

    /** 发布状态变更 */
    public void publishStatus(TaskEntity task, int status, String message) {
        ObjectNode extra = objectMapper.createObjectNode().put("status", status).put("message", message);
        publish(EVENT_STATUS, task, extra);
    }

    /** 发布进度变化 */
    public void publishProgress(TaskEntity task, int progress, int success, int fail, int total) {
        ObjectNode extra = objectMapper.createObjectNode()
                .put("progress", progress)
                .put("successCount", success)
                .put("failCount", fail)
                .put("totalCount", total);
        publish(EVENT_PROGRESS, task, extra);
    }

    private void publish(String event, TaskEntity task, ObjectNode extra) {
        try {
            ObjectNode message = objectMapper.createObjectNode()
                    .put("event", event)
                    .put("taskId", task.getId())
                    .put("userId", task.getUserId())
                    .put("projectId", task.getProjectId())
                    .put("taskType", task.getTaskType());
            if (extra != null) {
                message.setAll(extra);
            }
            redissonClient.getTopic(CHANNEL).publish(objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("[task-event] 发布失败 taskId={}: {}", task.getId(), e.getMessage());
        }
    }
}
