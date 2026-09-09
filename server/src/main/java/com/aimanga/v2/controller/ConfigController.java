package com.aimanga.v2.controller;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.ConfigVerifyRequest;
import com.aimanga.v2.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统配置(仅 ADMIN,由 Shiro admin 过滤器把守):
 * GET 返回脱敏值(***)、PUT 保存并热更新(密钥空串/*** 视为不修改)、POST verify 连通测试。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;
    private final AiService aiService;
    private final com.aimanga.v2.task.TaskWorkerPool taskWorkerPool;
    private final com.aimanga.v2.task.RedisSemaphores redisSemaphores;

    @GetMapping
    public Result<Map<String, String>> list() {
        return Result.ok(configService.toMaskedMap());
    }

    @PutMapping
    public Result<Map<String, String>> save(@RequestBody Map<String, Object> body) {
        Object configs = body.get("configs");
        if (!(configs instanceof Map<?, ?> rawMap)) {
            throw new BusinessException(400, "请求体应为 {configs: {key: value}}");
        }
        Map<String, String> toSave = new LinkedHashMap<>();
        rawMap.forEach((k, v) -> toSave.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        configService.save(toSave);
        // 任务系统热更新:Worker 池大小 + 分层并发信号量(层①②④)
        taskWorkerPool.refresh(configService.getInt("task_max_concurrency", 5));
        redisSemaphores.refresh();
        log.info("[admin] 系统配置已更新并热生效: {}", toSave.keySet());
        return Result.ok(configService.toMaskedMap());
    }

    /** 连通测试:kind = text | image */
    @PostMapping("/verify")
    public Result<Map<String, Object>> verify(@RequestBody ConfigVerifyRequest request) {
        String kind = request.kind() == null ? "text" : request.kind();
        String model = configService.getString("ai_" + kind + "_model");
        try {
            if ("image".equals(kind)) {
                String url = aiService.generateImage("image", "生成一张纯白色测试图,16x16 像素", java.util.List.of(), "1:1", null);
                return Result.ok(Map.of("ok", true, "message", "生图通道正常(model=" + model + ")", "url", url));
            }
            String reply = aiService.chat("text", "连通测试,请回复:pong", java.util.List.of());
            return Result.ok(Map.of("ok", true, "message", "文本通道正常(model=" + model + "): "
                    + reply.substring(0, Math.min(reply.length(), 50))));
        } catch (BusinessException e) {
            return Result.ok(Map.of("ok", false, "message", e.getMessage()));
        }
    }
}
