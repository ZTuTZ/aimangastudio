package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.PageMapper;
import com.aimanga.v2.service.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 成品页生成服务(Phase 6.3 T6.3.1 + Phase 8.1 fencing):
 * 页脚本 + 布局图 + 素材参考 + 风格 + 色彩 + 画幅 → AI → OSS,返回 PageGenResult。
 *
 * Phase 8.1:本服务只负责幂等检查与 AI/OSS 调用；页面状态变更由调用方在
 * StageItemCommitService 的双层 fencing 内完成，避免旧 Attempt 污染可见状态。
 *
 * 最终参考图顺序(T6.3.2):布局图置顶(构图约束) → 素材图(身份/环境约束)。
 * 页与页之间零依赖(T6.3.3)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageGenerationService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PageMapper pageMapper;
    private final PageReferenceResolver referenceResolver;
    private final PagePromptCompiler promptCompiler;
    private final AiService aiService;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    /** AI+OSS 结果(业务写入由 fenced commit 完成) */
    public record PageGenResult(String url, String mode, String prompt, List<String> images,
                                String inputUrl, Long chapterId, Integer pageNo) {}

    /** 处理单页成品:幂等检查 + AI + OSS;force=用户强制重生成时跳过幂等。
     *  T6.5.4:已有成品图但脚本版本更新(imageScriptVersion < scriptVersion)视为过期,不删旧图,直接重画。 */
    public PageGenResult processPage(Project project, PageEntity page, String colorMode, boolean force) {
        boolean fresh = page.getGeneratedImageUrl() != null && !page.getGeneratedImageUrl().isBlank()
                && page.getImageScriptVersion() != null
                && page.getImageScriptVersion().equals(orOne(page.getScriptVersion()));
        if (!force && fresh) {
            return new PageGenResult(page.getGeneratedImageUrl(),
                    colorMode == null || colorMode.isBlank() ? project.getColorMode() : colorMode,
                    "", List.of(), null, page.getChapterId(), page.getPageNo());
        }
        // page_direct_output=1:直接出成品,不依赖布局图(T6.4 配置开关)
        boolean directOutput = configService.getInt("page_direct_output", 0) == 1;
        if (!directOutput
                && (page.getLayoutImageUrl() == null || page.getLayoutImageUrl().isBlank())) {
            throw new BusinessException(400, "页面 " + page.getPageNo() + " 还没有布局图,请先生成布局");
        }
        PageReferenceResolver.ResolvedReferences refs =
                referenceResolver.resolve(project.getId(), page.getId(), project);
        // 最终参考图:布局图置顶(构图约束),其后为素材图(身份/环境约束);直接出图模式无布局图
        List<String> images = new ArrayList<>();
        if (!directOutput) {
            images.add(page.getLayoutImageUrl());
        }
        images.addAll(refs.imageUrls());
        String prompt = promptCompiler.compileFinalPagePrompt(project, page, refs.assetLabels(), colorMode, !directOutput);

        String mode = colorMode == null || colorMode.isBlank()
                ? (project.getColorMode() == null ? "partial" : project.getColorMode()) : colorMode;
        // §9 前半:OSS 转存完成;业务字段写入移交 fenced commit(Phase 8.1)
        String url = aiService.generateImage("image", prompt, images, project.getAspectRatio(), project.getUserId());
        log.info("[page-gen] 作品 {} 话{} 页#{} 成品页 AI+OSS 完成: {}",
                project.getId(), page.getChapterId(), page.getPageNo(), url);
        return new PageGenResult(url, mode, prompt, images,
                directOutput ? null : page.getLayoutImageUrl(), page.getChapterId(), page.getPageNo());
    }

    /** 成功业务写入(fenced commit 事务内调用):generated_image_url + records + image_script_version */
    public String applyPageImageResult(Long pageId, Integer scriptVersion, long imageRevision,
                                       PageGenResult result, String recordsJson) {
        int expectedScriptVersion = orOne(scriptVersion);
        String records = appendRecord(recordsJson, result.url(), result.mode());
        if (pageMapper.applyImageIfCurrent(pageId, expectedScriptVersion, imageRevision,
                result.url(), result.mode(), records) != 1) {
            throw new ContentVersionConflictException("页面内容版本已变化,成品结果已拒绝");
        }
        return "{\"pageId\":" + pageId + ",\"image\":\"" + result.url() + "\"}";
    }

    /** 临时状态也必须由调用方在 Stage Item + Task 双层 fencing 内写入。 */
    public void markPageRunning(Long pageId) {
        markPageStatus(pageId, PageEntity.GEN_RUNNING, null, null, null, null, null);
    }

    public void markPageFailed(Long pageId, String recordsJson, String colorMode, String reason) {
        markPageStatus(pageId, PageEntity.GEN_FAILED, null, null, reason,
                appendRecord(recordsJson, "", colorMode), null);
    }

    private static int orOne(Integer version) {
        return version == null ? 1 : version;
    }

    private void markPageStatus(Long pageId, Integer status, String url, String colorMode, String failReason,
                                String records, Integer imageScriptVersion) {
        PageEntity patch = new PageEntity();
        patch.setId(pageId);
        patch.setGenerateStatus(status);
        patch.setGeneratedImageUrl(url);
        patch.setColorMode(colorMode);
        patch.setFailReason(failReason);
        patch.setGenerateRecords(records);
        patch.setImageScriptVersion(imageScriptVersion);
        patch.setUpdateTime(LocalDateTime.now());
        pageMapper.updateById(patch);
    }

    /**
     * 生成记录追加(独立静态方法便于测试):JSON 数组,每项 {time,url,colorMode},保留最近 20 条。
     */
    public static String appendRecord(String recordsJson, String url, String colorMode) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode array = recordsJson == null || recordsJson.isBlank()
                    ? mapper.createArrayNode()
                    : (ArrayNode) mapper.readTree(recordsJson);
            ObjectNode record = mapper.createObjectNode();
            record.put("time", LocalDateTime.now().format(TS));
            record.put("url", url);
            record.put("colorMode", colorMode);
            array.add(record);
            while (array.size() > 20) {
                array.remove(0);
            }
            return mapper.writeValueAsString(array);
        } catch (Exception e) {
            return "[{\"time\":\"" + LocalDateTime.now().format(TS) + "\",\"url\":\"" + url + "\",\"colorMode\":\"" + colorMode + "\"}]";
        }
    }

    /** 解析生成记录(前端展示历史版本用) */
    public List<JsonNode> parseRecords(String recordsJson) {
        try {
            JsonNode node = objectMapper.readTree(recordsJson == null || recordsJson.isBlank() ? "[]" : recordsJson);
            List<JsonNode> list = new ArrayList<>();
            if (node.isArray()) {
                node.forEach(list::add);
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }
}
