package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.PageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 布局图生成服务(Phase 6.2 T6.2.3 + Phase 8.1):
 * Page + Prompt + ReferenceResolver → AI image → OSS。
 *
 * Phase 8.1 fencing:本服务只负责 幂等检查 + AI + OSS(返回 LayoutResult),
 * 业务表写入(page.layout_image_url / generation_record)由调用方通过
 * StageItemCommitService.commitFenced 在短事务内提交 —— 旧 Attempt 的业务写入会被拒绝。
 *
 * 布局图只解决构图约束(分格/站位/景别/阅读顺序),不做最终脸部/文字/材质。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LayoutGenerationService {

    private final PageMapper pageMapper;
    private final PageReferenceResolver referenceResolver;
    private final PagePromptCompiler promptCompiler;
    private final AiService aiService;

    /** AI+OSS 结果(业务写入由 fenced commit 完成) */
    public record LayoutResult(String url, String prompt, List<String> refUrls) {}

    /** 处理单页布局:返回 AI/OSS 结果;force=用户强制重布局时跳过幂等检查。
     *  T6.5.4:已有布局图但脚本版本已更新(layoutScriptVersion < scriptVersion)视为过期,自动重画。 */
    public LayoutResult processPage(Project project, PageEntity page, boolean force) {
        boolean fresh = page.getLayoutImageUrl() != null && !page.getLayoutImageUrl().isBlank()
                && page.getLayoutScriptVersion() != null
                && page.getLayoutScriptVersion().equals(orOne(page.getScriptVersion()));
        if (!force && fresh) {
            return new LayoutResult(page.getLayoutImageUrl(), "", List.of());
        }
        PageReferenceResolver.ResolvedReferences refs =
                referenceResolver.resolve(project.getId(), page.getId(), project);
        String prompt = promptCompiler.compileLayoutPrompt(project, page, refs.assetLabels());
        // OSS 转存已在 AiService 内完成;业务表写入由 fenced commit 完成(Phase 8.1)
        String url = aiService.generateImage("image", prompt, refs.imageUrls(),
                project.getAspectRatio(), project.getUserId());
        log.info("[layout] 作品 {} 页#{} 布局图已生成(AI+OSS): {}", project.getId(), page.getPageNo(), url);
        return new LayoutResult(url, prompt, refs.imageUrls());
    }

    /** 布局业务写入(fenced commit 事务内调用) */
    public void applyLayoutResult(Long pageId, Integer scriptVersion, LayoutResult result) {
        if (pageMapper.applyLayoutIfCurrent(pageId, orOne(scriptVersion), result.url()) != 1) {
            throw new ContentVersionConflictException("页面脚本已变化,布局结果已拒绝");
        }
    }

    private static int orOne(Integer version) {
        return version == null ? 1 : version;
    }

    public PageEntity page(Long pageId) {
        PageEntity page = pageMapper.selectById(pageId);
        if (page == null) {
            throw new BusinessException(404, "页面不存在: " + pageId);
        }
        return page;
    }
}
