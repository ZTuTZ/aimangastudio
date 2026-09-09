package com.aimanga.v2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 创建任务:payload 为任务参数 JSON(每类任务自行解析,如 MOCK 的 steps/sleepMs/failAt) */
public record CreateTaskRequest(
        @NotNull(message = "projectId 不能为空") Long projectId,
        Long chapterId,
        @NotBlank(message = "taskType 不能为空") String taskType,
        com.fasterxml.jackson.databind.JsonNode payload) {
}
