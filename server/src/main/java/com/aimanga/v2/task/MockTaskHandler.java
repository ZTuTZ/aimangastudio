package com.aimanga.v2.task;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MOCK 测试处理器:验证状态机/并发/停止/重试/恢复。
 * payload:{"steps":8,"sleepMs":600,"failAt":[3]} —— 3 号步模拟失败(任务终态 PARTIAL)。
 */
@Component
@RequiredArgsConstructor
public class MockTaskHandler implements TaskHandler {

    public static final String TYPE = "MOCK";

    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(TaskEntity task, TaskRuntime runtime) throws Exception {
        int steps = 8;
        long sleepMs = 600;
        List<Integer> failAt = new ArrayList<>();
        if (task.getPayload() != null && !task.getPayload().isBlank()) {
            try {
                JsonNode payload = objectMapper.readTree(task.getPayload());
                if (payload.has("steps") && payload.get("steps").isInt()) {
                    steps = Math.max(1, Math.min(100, payload.get("steps").asInt()));
                }
                if (payload.has("sleepMs") && payload.get("sleepMs").isInt()) {
                    sleepMs = Math.max(50, Math.min(10_000, payload.get("sleepMs").asLong()));
                }
                if (payload.has("failAt") && payload.get("failAt").isArray()) {
                    payload.get("failAt").forEach(n -> failAt.add(n.asInt()));
                }
            } catch (Exception e) {
                throw new BusinessException(400, "payload 解析失败: " + e.getMessage());
            }
        }

        runtime.begin(steps);
        for (int i = 1; i <= steps; i++) {
            runtime.checkStop();
            Thread.sleep(sleepMs);
            if (failAt.contains(i)) {
                runtime.stepFail("模拟失败步骤 " + i);
            } else {
                runtime.stepSuccess();
            }
        }
    }
}
