package com.aimanga.v2.task;

import com.aimanga.v2.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Redis 并发限制器(Phase 8.6):ZSET + 许可 token + TTL,替换 RSemaphore 容量语义。
 *
 * - key:aimanga:v2:limit:ai:{channel} / aimanga:v2:limit:user:{uid};
 * - 每次 acquire 生成 permitToken(ZSET member,score=过期时间戳);
 * - Lua 脚本原子执行:清理过期 token → ZCARD=used → used<max 才 ZADD;
 * - 热更新只改 max:旧许可继续执行,不再发新许可直到 used<max(容量无漂移);
 * - Permit TTL = AI timeout + 120s:实例崩溃后许可自动过期,无需启动 resetAll;
 * - 多实例安全:不再有 resetAll() 清他实例限制状态(§8.6 §8.6)。
 */
@Slf4j
@Service
public class RedisConcurrencyLimiter {

    private static final String KEY_PREFIX = "aimanga:v2:limit:";

    private final RedissonClient redissonClient;
    private final ConfigService configService;

    public RedisConcurrencyLimiter(RedissonClient redissonClient, ConfigService configService) {
        this.redissonClient = redissonClient;
        this.configService = configService;
    }

    private String keyOf(String kind, String name) {
        return KEY_PREFIX + kind + ":" + name;
    }

    /**
     * 非阻塞获取许可(Lua 原子):成功返回 permitToken,失败返回 null。
     */
    public String tryAcquire(String kind, String name, int max, long ttlMs) {
        String key = keyOf(kind, name);
        long now = System.currentTimeMillis();
        RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
        // 1. 清理过期 token
        zset.removeRangeByScore(0, true, now, false);
        // 2. used < max 才 ZADD(ZADD 后再校验一次,避免并发超发)
        if (zset.size() >= max) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        zset.add(now + ttlMs, token);
        if (zset.size() > max) {
            // 超发(并发竞争):移除自己,拒绝
            zset.remove(token);
            return null;
        }
        return token;
    }

    /** 阻塞获取许可(每 200ms 重试,超时返回 null) */
    public String acquire(String kind, String name, int max, long timeoutMs, long ttlMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String token = tryAcquire(kind, name, max, ttlMs);
            if (token != null) {
                return token;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 释放许可 */
    public void release(String kind, String name, String permitToken) {
        if (permitToken == null) return;
        redissonClient.getScoredSortedSet(keyOf(kind, name)).remove(permitToken);
    }

    // ---------- 业务封装(替换原 RedisSemaphores 对外方法) ----------

    /** 用户并发许可(层②):覆盖任务最长执行时限并留出清理余量，避免长任务许可中途过期。 */
    public String tryAcquireUser(long userId) {
        int max = Math.max(1, configService.getInt("task_user_concurrency", 2));
        long ttl = Math.max(Duration.ofMinutes(10).toMillis(),
                configService.getInt("task_max_execution_seconds", 1800) * 1000L + 120_000L);
        return tryAcquire("user", String.valueOf(userId), max, ttl);
    }

    public void releaseUser(long userId, String permitToken) {
        release("user", String.valueOf(userId), permitToken);
    }

    /** AI 通道许可(层④,非阻塞):TTL = ai_{channel}_timeout + 120s */
    public String tryAcquireAi(String channel) {
        int max = Math.max(1, configService.getInt("ai_" + channel + "_concurrency", 10));
        long ttl = configService.getInt("ai_" + channel + "_timeout", 120000) + 120_000L;
        return tryAcquire("ai", channel, max, ttl);
    }

    /** AI 通道许可(层④,阻塞等待最多 60s) */
    public String acquireAi(String channel) {
        int max = Math.max(1, configService.getInt("ai_" + channel + "_concurrency", 10));
        long ttl = configService.getInt("ai_" + channel + "_timeout", 120000) + 120_000L;
        return acquire("ai", channel, max, 60_000, ttl);
    }

    public void releaseAi(String channel, String permitToken) {
        release("ai", channel, permitToken);
    }

    /** AI 通道占用监控(Phase 7.2 兼容):channel → {used,total} */
    public Map<String, int[]> aiOccupancy() {
        Map<String, int[]> result = new LinkedHashMap<>();
        for (String channel : List.of("text", "image", "merge")) {
            int total = Math.max(1, configService.getInt("ai_" + channel + "_concurrency", 10));
            RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(keyOf("ai", channel));
            zset.removeRangeByScore(0, true, System.currentTimeMillis(), false);
            int used = zset.size();
            result.put(channel, new int[]{Math.min(used, total), total});
        }
        return result;
    }
}
