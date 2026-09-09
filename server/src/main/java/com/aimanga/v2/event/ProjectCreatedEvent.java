package com.aimanga.v2.event;

import com.aimanga.v2.model.Project;

/** 作品创建事件(粘贴创建/批量导入后发布),任务系统监听后自动入队 SPLIT 拆话 */
public record ProjectCreatedEvent(Project project) {
}
