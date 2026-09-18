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
import java.util.List;

/**
 * 页面后处理服务(Phase 6.6 + Phase 8.1 fencing):COLORIZE / CLEAN / REPAINT。
 * 统一使用 merge 通道 + 当前成品图作为输入;AI+OSS 完成后返回 PostProcessResult,
 * 业务写入(generated_image_url / generate_records)由调用方通过 StageItemCommitService.commitFenced 完成。
 * 旧图永远保留在 generate_records 中;image_script_version 保持当前脚本版本。
 * 继续复用 Task/Stage Item/ConcurrentRunner 架构(T6.6.3):每类后处理独立 Stage,互不抢 Item。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostProcessService {

    public static final String OP_COLORIZE = "COLORIZE";
    public static final String OP_CLEAN = "CLEAN";
    public static final String OP_REPAINT = "REPAINT";

    private final PageMapper pageMapper;
    private final AiService aiService;

    /** AI+OSS 结果(业务写入由 fenced commit 完成) */
    public record PostProcessResult(String url, String op, String prompt, List<String> images) {}

    /** AI + OSS:op = COLORIZE/CLEAN/REPAINT;REPAINT 需要 repaintPrompt 与 maskUrl。 */
    public PostProcessResult execute(Project project, PageEntity page, String op,
                                     String repaintPrompt, String maskUrl, String colorMode) {
        if (page.getGeneratedImageUrl() == null || page.getGeneratedImageUrl().isBlank()) {
            throw new BusinessException(400, "页面 " + page.getPageNo() + " 还没有成品图,无法进行后处理");
        }
        String input = page.getGeneratedImageUrl();
        String prompt;
        List<String> images;
        switch (op) {
            case OP_COLORIZE -> {
                String mode = colorMode == null || colorMode.isBlank() ? "color" : colorMode;
                prompt = "将这张漫画页进行" + (mode.equals("monochrome") ? "高质量黑白灰阶重制(清晰层次)"
                        : mode.equals("partial") ? "局部上色(以黑白为主,关键色彩点缀)" : "全彩上色")
                        + ":严格保持线稿、构图、人物形象与分格不变,只改变色彩;不要新增或删除任何元素;不要文字。";
                images = List.of(input);
            }
            case OP_CLEAN -> {
                prompt = "对这张漫画页做清晰化修复:提升线条质量、锐度与网点/灰阶表现,修补噪点与伪影;"
                        + "严格保持构图、人物形象与内容不变;不要文字、不要水印。";
                images = List.of(input);
            }
            case OP_REPAINT -> {
                if (repaintPrompt == null || repaintPrompt.isBlank()) {
                    throw new BusinessException(400, "局部重绘必须提供重绘提示词");
                }
                if (maskUrl == null || maskUrl.isBlank()) {
                    throw new BusinessException(400, "局部重绘必须提供遮罩图(白色=重绘区域)");
                }
                prompt = "局部重绘:只修改遮罩区域(第二张图为遮罩,白色为需要重绘的区域),重绘内容: " + repaintPrompt.trim()
                        + ";遮罩以外的区域必须与原图保持像素级一致;不要文字、不要水印。";
                images = List.of(input, maskUrl);
            }
            default -> throw new BusinessException(400, "未知后处理类型: " + op);
        }
        String url = aiService.generateImage("merge", prompt, images, project.getAspectRatio(), project.getUserId());
        log.info("[post-process] 作品 {} 页#{} {} AI+OSS 完成: {}", project.getId(), page.getPageNo(), op, url);
        return new PostProcessResult(url, op, prompt, images);
    }

    /** 业务写入(fenced commit 事务内调用):更新成品图 + 追加 op 记录,返回 resultRef */
    public String applyResult(Project project, PageEntity page, PostProcessResult result, String repaintPrompt) {
        PageEntity patch = new PageEntity();
        patch.setId(page.getId());
        patch.setGeneratedImageUrl(result.url());
        patch.setGenerateStatus(PageEntity.GEN_SUCCESS);
        patch.setFailReason("");
        patch.setGenerateRecords(appendOpRecord(page.getGenerateRecords(), result.op(), result.url(), repaintPrompt));
        patch.setImageScriptVersion(page.getScriptVersion() == null ? 1 : page.getScriptVersion());
        patch.setUpdateTime(LocalDateTime.now());
        pageMapper.updateById(patch);
        return "{\"pageId\":" + page.getId() + ",\"url\":\"" + result.url() + "\"}";
    }

    private static String appendOpRecord(String recordsJson, String op, String url, String repaintPrompt) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var array = recordsJson == null || recordsJson.isBlank()
                    ? mapper.createArrayNode() : (com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree(recordsJson);
            var record = mapper.createObjectNode();
            record.put("time", java.time.LocalDateTime.now().toString());
            record.put("op", op);
            record.put("url", url);
            if (repaintPrompt != null && !repaintPrompt.isBlank()) {
                record.put("repaintPrompt", repaintPrompt);
            }
            array.add(record);
            while (array.size() > 20) {
                array.remove(0);
            }
            return mapper.writeValueAsString(array);
        } catch (Exception e) {
            return PageGenerationService.appendRecord(recordsJson, url, op);
        }
    }
}
