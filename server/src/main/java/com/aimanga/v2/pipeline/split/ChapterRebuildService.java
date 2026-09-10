package com.aimanga.v2.pipeline.split;

import com.aimanga.v2.model.Chapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 分话重建(独立 Bean 保证 @Transactional 生效):
 * 覆盖校验通过后,一次性删除旧话并插入全部新话,避免规划过程中破坏旧数据。
 */
@Service
@RequiredArgsConstructor
public class ChapterRebuildService {

    private final com.aimanga.v2.repository.ChapterMapper chapterMapper;

    @Transactional
    public List<Chapter> rebuild(Long projectId, String sourceText, List<ChapterPlan> plans) {
        chapterMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId));
        List<Chapter> created = new ArrayList<>();
        for (ChapterPlan plan : plans) {
            Chapter chapter = new Chapter();
            chapter.setProjectId(projectId);
            chapter.setChapterNo(plan.chapterNo());
            chapter.setTitle(plan.title());
            // scriptText 由 Java 按 offset 从原始 source_text 截取,保证与原文逐字符一致
            chapter.setScriptText(sourceText.substring(plan.startOffset(), plan.endOffset()));
            chapter.setStatus(Chapter.STATUS_PENDING);
            chapter.setPageCount(0);
            chapter.setCreateTime(LocalDateTime.now());
            chapterMapper.insert(chapter);
            created.add(chapter);
        }
        return created;
    }
}
