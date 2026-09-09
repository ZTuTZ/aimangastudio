package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.ChapterVO;
import com.aimanga.v2.dto.CreateChapterRequest;
import com.aimanga.v2.dto.UpdateChapterRequest;
import com.aimanga.v2.model.Chapter;
import com.aimanga.v2.service.ChapterService;
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
}
