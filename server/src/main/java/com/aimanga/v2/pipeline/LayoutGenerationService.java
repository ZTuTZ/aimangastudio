package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.PageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 布局图生成服务(Phase 6.2 T6.2.3):
 * Page + Prompt + ReferenceResolver → AI image → OSS → page.layout_image_url。
 *
 * 布局图只解决构图约束(分格/站位/景别/阅读顺序),不做最终脸部/文字/材质;
 * 真正成品页由 PageGenerationService(Phase 6.3)以布局图为第 1 参考图生成。
 *
 * 幂等(T6.2.5):layout_image_url 非空且非强制 → 直接返回现有图,不重复调用 AI;
 * 手动重布局通过 forceReset 对应 LAYOUT Item(force 标记)实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LayoutGenerationService {

    private final PageMapper pageMapper;
    private final PageReferenceResolver referenceResolver;
    private final PagePromptCompiler promptCompiler;
    private final AiService aiService;

    /** 处理单页布局:返回布局图 URL;force=用户强制重布局时跳过幂等检查 */
    public String processPage(Project project, PageEntity page, boolean force) {
        if (!force && page.getLayoutImageUrl() != null && !page.getLayoutImageUrl().isBlank()) {
            return page.getLayoutImageUrl();
        }
        PageReferenceResolver.ResolvedReferences refs =
                referenceResolver.resolve(project.getId(), page.getId(), project);
        String prompt = promptCompiler.compileLayoutPrompt(project, page, refs.assetLabels());
        // OSS 转存已在 AiService 内完成(§9:先保存 OSS → 更新业务字段 → 由 Runner 标 Item SUCCESS)
        String url = aiService.generateImage("image", prompt, refs.imageUrls(),
                project.getAspectRatio(), project.getUserId());
        PageEntity patch = new PageEntity();
        patch.setId(page.getId());
        patch.setLayoutImageUrl(url);
        patch.setUpdateTime(LocalDateTime.now());
        pageMapper.updateById(patch);
        log.info("[layout] 作品 {} 页#{} 布局图已生成: {}", project.getId(), page.getPageNo(), url);
        return url;
    }

    public PageEntity page(Long pageId) {
        PageEntity page = pageMapper.selectById(pageId);
        if (page == null) {
            throw new BusinessException(404, "页面不存在: " + pageId);
        }
        return page;
    }
}
