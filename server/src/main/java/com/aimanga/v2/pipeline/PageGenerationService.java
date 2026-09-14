package com.aimanga.v2.pipeline;

import com.aimanga.v2.ai.AiService;
import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import com.aimanga.v2.repository.PageMapper;
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
 * 成品页生成服务(Phase 6.3 T6.3.1):
 * 页脚本 + 布局图 + PageReferenceResolver 素材 + 风格 + 色彩 + 画幅 → AI → OSS → page.generated_image_url。
 *
 * 最终参考图顺序(T6.3.2):布局图(构图约束)置顶 → 角色设定表/参考图 → 场景 → 服装 → 道具 → 风格图(身份/环境约束)。
 * 写入顺序(T6.3.1 铁律):AI 返回 → OSS 保存 → 写 generated_image_url → 追加 generate_records → 由 Runner 标 Item SUCCESS。
 * 页与页之间零依赖(T6.3.3):跨页一致性由资产设定表/参考图/风格保证,前一页结果只作低优先级可选参考。
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
    private final ObjectMapper objectMapper;

    /** 处理单页成品:返回成品图 URL;force=用户强制重生成时跳过幂等。
     *  T6.5.4:已有成品图但脚本版本更新(imageScriptVersion < scriptVersion)视为过期,不删旧图,直接重画。 */
    public String processPage(Project project, PageEntity page, String colorMode, boolean force) {
        boolean fresh = page.getGeneratedImageUrl() != null && !page.getGeneratedImageUrl().isBlank()
                && page.getImageScriptVersion() != null
                && page.getImageScriptVersion().equals(orOne(page.getScriptVersion()));
        if (!force && fresh) {
            return page.getGeneratedImageUrl();
        }
        if (page.getLayoutImageUrl() == null || page.getLayoutImageUrl().isBlank()) {
            throw new BusinessException(400, "页面 " + page.getPageNo() + " 还没有布局图,请先生成布局");
        }
        markPageStatus(page.getId(), PageEntity.GEN_RUNNING, null, null, null, null, null);
        PageReferenceResolver.ResolvedReferences refs =
                referenceResolver.resolve(project.getId(), page.getId(), project);
        // 最终参考图:布局图置顶(构图约束),其后为素材图(身份/环境约束)
        List<String> images = new ArrayList<>();
        images.add(page.getLayoutImageUrl());
        images.addAll(refs.imageUrls());
        String prompt = promptCompiler.compileFinalPagePrompt(project, page, refs.assetLabels(), colorMode);

        String mode = colorMode == null || colorMode.isBlank()
                ? (project.getColorMode() == null ? "partial" : project.getColorMode()) : colorMode;
        try {
            // §9:先 OSS 转存 → 写业务字段 → 追加 generate_records → Runner 标 Item SUCCESS
            String url = aiService.generateImage("image", prompt, images, project.getAspectRatio(), project.getUserId());
            markPageStatus(page.getId(), PageEntity.GEN_SUCCESS, url, mode, null,
                    appendRecord(page.getGenerateRecords(), url, mode), orOne(page.getScriptVersion()));
            log.info("[page-gen] 作品 {} 话{} 页#{} 成品页已生成: {}", project.getId(), page.getChapterId(), page.getPageNo(), url);
            return url;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            markPageStatus(page.getId(), PageEntity.GEN_FAILED, null, null,
                    reason, appendRecord(page.getGenerateRecords(), "", mode), null);
            throw e;
        }
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
     * 生成记录追加(独立静态方法便于测试):失败时也保留最近一次失败的 URL 信息由调用方决定。
     * 格式:JSON 数组,每项 {time,url,colorMode}。
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
            // 只保留最近 20 条,避免长期生产把行撑爆
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
