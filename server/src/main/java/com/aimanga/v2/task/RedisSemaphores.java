package com.aimanga.v2.task;

import com.aimanga.v2.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 信号量池(单实例可跑,多实例天然共享):
 * - 每用户并行任务数:aimanga:v2:sem:user:{uid}(层②)
 * - AI 通道并发:aimanga:v2:sem:ai:{channel}(层④)
 * 配置热更新:refresh() 按新配置值调整已存在信号量的许可数(增加→release,减少→尝试回收空闲许可)。
 * 活跃 key 记录在 aimanga:v2:sem:active,便于全量刷新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSemaphores {

    public static final String ACTIVE_KEY = "aimanga:v2:sem:active";

    private final RedissonClient redissonClient;
    private final ConfigService configService;

    /** 本进程已用过的信号量名(刷新时优先处理,减少 Redis 扫描) */
    private final Set<String> localNames = ConcurrentHashMap.newKeySet();

    /** 尝试获取用户并发许可(层②) */
    public boolean tryAcquireUser(long userId) {
        return tryAcquire("user", "user:" + userId, configService.getInt("task_user_concurrency", 2));
    }

    public void releaseUser(long userId) {
        release("user:" + userId);
    }

    /** 尝试获取 AI 通道许可(层④,非阻塞) */
    public boolean tryAcquireAi(String channel) {
        return tryAcquire("ai", "ai:" + channel, configService.getInt("ai_" + channel + "_concurrency", 10));
    }

    /** 获取 AI 通道许可(层④,阻塞等待最多 60s,供流水线调用) */
    public boolean acquireAi(String channel) {
        return acquire("ai", "ai:" + channel, configService.getInt("ai_" + channel + "_concurrency", 10), 60_000);
    }

    public void releaseAi(String channel) {
        release("ai:" + channel);
    }

    /** 配置热更新:调整所有活跃信号量的许可数 */
    public void refresh() {
        for (String name : localNames) {
            String group = name.startsWith("user:") ? "user" : "ai";
            int target = name.startsWith("user:")
                    ? configService.getInt("task_user_concurrency", 2)
                    : configService.getInt("ai_" + name.substring(3) + "_concurrency", 10);
            adjust(redissonClient.getSemaphore(semKey(group, name)), target);
        }
    }

    /**
     * 启动时全量重置:MySQL 是任务唯一事实,启动瞬间没有任何任务在跑,
     * 上次停机(尤其强杀)泄漏的许可此时清零最安全。
     */
    public void resetAll() {
        localNames.clear();
        redissonClient.getKeys().deleteByPattern("aimanga:v2:sem:*");
        log.info("[semaphore] 已重置全部并发信号量(消除停机泄漏的许可)");
    }

    private boolean tryAcquire(String group, String name, int permits) {
        RSemaphore semaphore = redissonClient.getSemaphore(semKey(group, name));
        if (!semaphore.isExists()) {
            semaphore.trySetPermits(Math.max(1, permits));
            redissonClient.getSet(ACTIVE_KEY).add(group + ":" + name);
        }
        boolean acquired = semaphore.tryAcquire();
        if (acquired) {
            localNames.add(name);
            redissonClient.getSet(ACTIVE_KEY).add(group + ":" + name);
        }
        return acquired;
    }

    /** 阻塞获取(带超时) */
    private boolean acquire(String group, String name, int permits, long timeoutMs) {
        RSemaphore semaphore = redissonClient.getSemaphore(semKey(group, name));
        if (!semaphore.isExists()) {
            semaphore.trySetPermits(Math.max(1, permits));
            redissonClient.getSet(ACTIVE_KEY).add(group + ":" + name);
        }
        localNames.add(name);
        redissonClient.getSet(ACTIVE_KEY).add(group + ":" + name);
        try {
            return semaphore.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void release(String name) {
        String group = name.startsWith("user:") ? "user" : "ai";
        redissonClient.getSemaphore(semKey(group, name)).release();
    }

    private String semKey(String group, String name) {
        return "aimanga:v2:sem:" + group + ":" + name;
    }

    /** 调整许可数到目标值(只处理本进程见过的 key,配合多实例时各自收敛) */
    private void adjust(RSemaphore semaphore, int target) {
        try {
            int current = semaphore.availablePermits();
            while (current < target) {
                semaphore.release();
                current++;
            }
            while (current > target && semaphore.tryAcquire()) {
                current--;
            }
        } catch (Exception ignored) {
            // 刷新失败不影响运行,下次保存配置再收敛
        }
    }
}
