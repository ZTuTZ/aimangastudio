package com.aimanga.v2.task;

import com.aimanga.v2.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE 事件网关:订阅 Redis 频道 aimanga:v2:events,按任务归属把事件推给对应用户的浏览器;
 * ADMIN 可订阅全局(scope=all)。浏览器侧 EventSource 自动重连;断线期间任务列表降级轮询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskSseHub {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /** 普通用户:userId → emitters */
    private final Map<Long, List<SseEmitter>> userEmitters = new ConcurrentHashMap<>();
    /** ADMIN 全局订阅:emitter → userId */
    private final Map<SseEmitter, Long> adminEmitters = new ConcurrentHashMap<>();

    private ScheduledExecutorService heartbeat;

    @PostConstruct
    public void init() {
        redissonClient.getTopic(TaskEventPublisher.CHANNEL).addListener(String.class, (channel, message) -> dispatch(message));
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(this::pingAll, 30, 30, TimeUnit.SECONDS);
        log.info("[sse] 任务事件网关已启动");
    }

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdownNow();
    }

    public SseEmitter register(long userId, boolean global) {
        SseEmitter emitter = new SseEmitter(0L);
        Runnable cleanup = () -> {
            userEmitters.values().forEach(list -> list.remove(emitter));
            adminEmitters.remove(emitter);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"ok\":true}"));
        } catch (IOException ignored) {
            // 客户端立即断开
        }
        userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        if (global && CurrentUser.isAdmin()) {
            adminEmitters.put(emitter, userId);
        }
        log.info("[sse] 客户端接入 userId={} global={} 在线={}", userId, global,
                userEmitters.values().stream().mapToInt(List::size).sum() + adminEmitters.size());
        return emitter;
    }

    private void dispatch(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            long userId = node.path("userId").asLong(0);
            String event = node.path("event").asText("task.status");
            String data = node.toString();
            sendToUser(userId, event, data);
            adminEmitters.keySet().forEach(emitter -> send(emitter, event, data));
        } catch (Exception e) {
            log.warn("[sse] 事件分发失败: {}", e.getMessage());
        }
    }

    private void sendToUser(long userId, String event, String data) {
        List<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters != null) {
            emitters.forEach(emitter -> send(emitter, event, data));
        }
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            emitter.complete();
        }
    }

    private void pingAll() {
        for (List<SseEmitter> list : userEmitters.values()) {
            list.forEach(emitter -> send(emitter, "ping", "{\"ts\":" + System.currentTimeMillis() + "}"));
        }
        adminEmitters.keySet().forEach(emitter -> send(emitter, "ping", "{\"ts\":" + System.currentTimeMillis() + "}"));
    }
}
