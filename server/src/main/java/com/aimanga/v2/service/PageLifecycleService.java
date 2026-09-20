package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.PipelineStageItem;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.repository.PipelineStageItemMapper;
import com.aimanga.v2.repository.TaskPlanUnitMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;

/** Owns destructive page replacement so stale Items and plans cannot survive a script rebuild. */
@Service
@RequiredArgsConstructor
public class PageLifecycleService {

    private final PageMapper pageMapper;
    private final PipelineStageItemMapper itemMapper;
    private final TaskPlanUnitMapper planUnitMapper;

    @Transactional
    public <T> T replaceChapterPages(Long projectId, Long chapterId, Long ownerTaskId, Supplier<T> writer) {
        List<Long> oldPageIds = pageMapper.selectList(new LambdaQueryWrapper<PageEntity>()
                        .eq(PageEntity::getProjectId, projectId)
                        .eq(PageEntity::getChapterId, chapterId)
                        .orderByAsc(PageEntity::getId))
                .stream().map(PageEntity::getId).toList();
        if (!oldPageIds.isEmpty() && planUnitMapper.countActivePageOwners(ownerTaskId, projectId, oldPageIds) > 0) {
            throw new BusinessException(409, "本话仍有页面生成任务正在执行或暂停,请先停止后再重生成脚本");
        }
        if (!oldPageIds.isEmpty()) {
            planUnitMapper.cancelPageUnits(oldPageIds, "页面因脚本重生成被替换");
            itemMapper.delete(new LambdaQueryWrapper<PipelineStageItem>()
                    .eq(PipelineStageItem::getProjectId, projectId)
                    .eq(PipelineStageItem::getBusinessType, "PAGE")
                    .in(PipelineStageItem::getBusinessId, oldPageIds));
            pageMapper.deleteBatchIds(oldPageIds);
        }
        T result = writer.get();
        itemMapper.deletePageOrphans(projectId);
        return result;
    }
}
