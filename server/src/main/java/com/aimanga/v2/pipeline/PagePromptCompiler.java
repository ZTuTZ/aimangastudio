package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.model.Project;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 页级 Prompt 统一编译器(Phase 6.2 T6.2.2):
 * LAYOUT / IMAGE Handler 禁止各自拼 prompt,统一由此生成。
 *
 * 职责分离:
 * - 布局图(LayoutPrompt):只解决 格子布局/人物站位/景别/动作关系/阅读顺序;
 *   黑白线稿草案,不追求最终精细脸部、文字准确性、材质;
 * - 成品页(FinalPagePrompt):依据布局线稿 + 素材参考生成最终页;对话框留白不写字
 *   (对白由排版叠加,避免 AI 文字渲染错误)。
 */
@Service
@RequiredArgsConstructor
public class PagePromptCompiler {

    /** 布局图 prompt */
    public String compileLayoutPrompt(Project project, PageEntity page, List<String> assetLabels) {
        StringBuilder sb = new StringBuilder();
        sb.append("为漫画页绘制一张分镜布局线稿草图。这是构图草案,不是成品。\n");
        sb.append("只解决:页内分格布局、人物站位、景别(远/中/近特写)、动作关系、阅读顺序。\n");
        sb.append("不要追求:精细脸部、材质、上色、文字渲染。\n");
        sb.append("整页必须铺满画布,无边距、无外围留白。\n");
        sb.append("分镜要求:必须使用以下分镜技巧至少一种——斜切分镜/画中画/多格拼贴/破格构图/特写/氛围空镜;禁止单一的上下结构。\n\n");
        sb.append("页面内容:\n");
        if (page.getNarration() != null && !page.getNarration().isBlank()) {
            sb.append("旁白:").append(page.getNarration().trim()).append("\n");
        }
        sb.append("对白:\n").append(dialogueText(page)).append("\n");
        if (page.getVisual() != null && !page.getVisual().isBlank()) {
            sb.append("画面描述:").append(page.getVisual().trim()).append("\n");
        }
        if (page.getSceneDescription() != null && !page.getSceneDescription().isBlank()) {
            sb.append("场景描述:").append(page.getSceneDescription().trim()).append("\n");
        }
        if (assetLabels != null && !assetLabels.isEmpty()) {
            sb.append("\n本页素材(按参考图顺序):\n");
            assetLabels.forEach(l -> sb.append("- ").append(l).append("\n"));
        }
        sb.append("\n要求:\n");
        sb.append("- 黑白线稿,简洁笔触,分格边框清晰;\n");
        sb.append("- 对话框画成空白气泡/框,严禁出现任何文字;\n");
        sb.append("- 人物用简化形体表达站位与动作,头部可用简单轮廓;\n");
        sb.append("- 画面保持干净,不要水印、不要签名。\n");
        String style = styleOf(project);
        if (!style.isBlank()) {
            sb.append("风格基调:").append(style).append("\n");
        }
        sb.append("画幅:").append(PipelineContext.safe(project.getAspectRatio())).append("。");
        return sb.toString();
    }

    /**
     * 成品页 prompt(Phase 6.3 PAGE 阶段使用)。
     * withLayout=true:以布局线稿为第 1 参考图,严格遵循构图;
     * withLayout=false(page_direct_output=1 直接出图模式):由模型自行设计分镜,其余锚定要求不变。
     */
    public String compileFinalPagePrompt(Project project, PageEntity page, List<String> assetLabels,
                                         String colorMode, boolean withLayout) {
        StringBuilder sb = new StringBuilder();
        if (withLayout) {
            sb.append("依据给定的布局线稿(第 1 张参考图,构图约束)绘制最终漫画成品页。\n");
            sb.append("布局线稿只约束构图与站位,请在此基础上精细化:清晰的线稿、明确的黑白灰关系或上色。\n\n");
        } else {
            sb.append("直接绘制最终漫画成品页。\n");
            sb.append("自行设计本页分镜布局并精细化:清晰的线稿、明确的黑白灰关系或上色。\n\n");
        }
        sb.append("页面内容:\n");
        if (page.getNarration() != null && !page.getNarration().isBlank()) {
            sb.append("旁白:").append(page.getNarration().trim()).append("\n");
        }
        sb.append("对白:\n").append(dialogueText(page)).append("\n");
        if (page.getVisual() != null && !page.getVisual().isBlank()) {
            sb.append("画面描述:").append(page.getVisual().trim()).append("\n");
        }
        if (page.getSceneDescription() != null && !page.getSceneDescription().isBlank()) {
            sb.append("场景描述:").append(page.getSceneDescription().trim()).append("\n");
        }
        if (assetLabels != null && !assetLabels.isEmpty()) {
            sb.append("\n角色与素材(按后续参考图顺序,身份约束):\n");
            assetLabels.forEach(l -> sb.append("- ").append(l).append("\n"));
        }
        sb.append("\n要求:\n");
        sb.append(withLayout
                ? "- 严格遵循第 1 张布局线稿的分格结构、人物站位与景别;\n- 布局线稿中的每一个分格都必须完整画出,不得遗漏任何格子,不得放大某格导致其他格子被挤出画布;\n- 旁白框(矩形)与对白气泡必须全部保留,位置与大小与布局线稿一致,并完整呈现在画布内;\n"
                : "- 每一个分格都必须完整呈现在画布内,不得放大某格导致其他格子被挤出画布;\n- 旁白框(矩形)与对白气泡必须全部画出并完整呈现在画布内;\n");
        sb.append("- 整页铺满画布,格与格之间保持白色分隔线,画布边缘不留大片空白,任何内容不得超出画布边缘;\n");
        sb.append("- 分镜技巧参考:斜切分镜/画中画/多格拼贴/破格构图/特写/氛围空镜;\n");
        sb.append("- 人物长相/发型/服装严格与对应角色参考图保持一致;\n");
        sb.append("- 对话框内留白,严禁绘制任何文字;\n");
        sb.append("- 色彩模式:").append(colorModeLabel(colorMode)).append(";\n");
        sb.append("- 不要水印、不要签名、不要页码。\n");
        String style = styleOf(project);
        if (!style.isBlank()) {
            sb.append("整体风格:").append(style).append("\n");
        }
        sb.append("画幅:").append(PipelineContext.safe(project.getAspectRatio())).append("。");
        return sb.toString();
    }

    private String dialogueText(PageEntity page) {
        List<StoryScript.DialogueItem> dialogue = PipelineUtils.parseDialogueLines(
                page.getDialogue() == null ? "[]" : page.getDialogue());
        if (dialogue == null || dialogue.isEmpty()) {
            return "(本页无对白)";
        }
        StringBuilder sb = new StringBuilder();
        for (StoryScript.DialogueItem d : dialogue) {
            sb.append("- ").append(PipelineContext.safe(d.speaker())).append(":")
                    .append(PipelineContext.safe(d.line())).append("\n");
        }
        return sb.toString();
    }

    private String colorModeLabel(String colorMode) {
        return switch (colorMode == null ? "partial" : colorMode) {
            case "monochrome" -> "黑白(仅黑白灰)";
            case "color" -> "全彩";
            default -> "局部上色(以黑白为主,关键色点缀)";
        };
    }

    private String styleOf(Project project) {
        // 风格由 prompt 渲染层与参考图共同承担;这里透传作品备注的风格基调(可空)
        return "";
    }
}
