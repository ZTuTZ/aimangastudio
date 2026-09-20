package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.ChapterVO;
import com.aimanga.v2.dto.CreateChapterRequest;
import com.aimanga.v2.dto.UpdateChapterRequest;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.service.ChapterService;
import com.aimanga.v2.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChapterController {

    private final ChapterService chapterService;
    private final TaskService taskService;

    @GetMapping("/projects/{projectId}/chapters")
    public Result<List<ChapterVO>> listByProject(@PathVariable Long projectId) {
        return Result.ok(chapterService.listByProject(projectId));
    }

    @PostMapping("/projects/{projectId}/chapters")
    public Result<ChapterVO> create(@PathVariable Long projectId, @RequestBody CreateChapterRequest request) {
        return Result.ok(chapterService.toVO(chapterService.create(projectId, request)));
    }

    @PutMapping("/chapters/{id}")
    public Result<ChapterVO> update(@PathVariable Long id, @RequestBody UpdateChapterRequest request) {
        return Result.ok(chapterService.toVO(chapterService.update(id, request)));
    }

    /** 重新生成本话脚本(创建 SCRIPT 任务) */
    @PostMapping("/chapters/{id}/regenerate-script")
    public Result<com.aimanga.v2.dto.TaskVO> regenerateScript(@PathVariable Long id) {
        Chapter chapter = chapterService.requireAccessible(id);
        com.fasterxml.jackson.databind.node.ObjectNode payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.put("chapterId", id);
        payload.put("forceScript", true);
        return Result.ok(taskService.create(
                new com.aimanga.v2.dto.CreateTaskRequest(chapter.getProjectId(), chapter.getId(), "SCRIPT", payload)));
    }
}
