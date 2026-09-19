package com.aimanga.v2.task;

/**
 * 当前 Task 执行代次的所有权凭证。
 *
 * Stage Item 在被领取时会保存该凭证；提交业务结果时必须同时验证 Task 与
 * Item 的凭证，避免已被看门狗接管的旧 Worker 继续写入正式结果。
 */
public record TaskExecutionOwner(Long taskId, String claimToken) {
}
