package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康探针:验证 MySQL 与 Redis 连通性(免鉴权,部署探活/开发自检用)。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final RedissonClient redissonClient;

    public HealthController(JdbcTemplate jdbcTemplate, RedissonClient redissonClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.redissonClient = redissonClient;
    }

    @GetMapping("/health")
    public Result<Map<String, String>> health() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("db", checkDb());
        status.put("redis", checkRedis());
        boolean allUp = "UP".equals(status.get("db")) && "UP".equals(status.get("redis"));
        status.put("status", allUp ? "UP" : "DOWN");
        return Result.ok(status);
    }

    private String checkDb() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            log.warn("[health] MySQL 探测失败: {}", e.getMessage());
            return "DOWN";
        }
    }

    private String checkRedis() {
        try {
            return redissonClient.getNodesGroup().pingAll() ? "UP" : "DOWN";
        } catch (Exception e) {
            log.warn("[health] Redis 探测失败: {}", e.getMessage());
            return "DOWN";
        }
    }
}
