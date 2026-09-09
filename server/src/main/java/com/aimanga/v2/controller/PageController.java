package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import com.aimanga.v2.dto.PageVO;
import com.aimanga.v2.dto.UpdatePageRequest;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.service.PageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
public class PageController {

    private final PageService pageService;

    @GetMapping("/chapters/{chapterId}/pages")
    public Result<List<PageVO>> listByChapter(@PathVariable Long chapterId) {
        return Result.ok(pageService.listByChapter(chapterId));
    }

    @PostMapping("/chapters/{chapterId}/pages")
    public Result<PageVO> create(@PathVariable Long chapterId, @RequestBody(required = false) UpdatePageRequest request) {
        return Result.ok(pageService.toVO(pageService.create(chapterId, request == null ? new UpdatePageRequest(null, null, null, null) : request)));
    }

    @PutMapping("/pages/{id}")
    public Result<PageVO> update(@PathVariable Long id, @RequestBody UpdatePageRequest request) {
        return Result.ok(pageService.toVO(pageService.update(id, request)));
    }

    @DeleteMapping("/pages/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        pageService.delete(id);
        return Result.ok();
    }
}
