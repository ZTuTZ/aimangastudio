package com.aimanga.v2.ai;

import com.aimanga.v2.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 提示词模板渲染:模板存 system_config(prompt_*),留空回退代码内置默认;
 * 占位符 {text}/{aspect}/{characters}/{page_count}/{style}/{color_mode} 等。
 */
@Service
@RequiredArgsConstructor
public class PromptService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_]+)}");

    private final ConfigService configService;

    public String render(String configKey, String defaultTemplate, Map<String, String> vars) {
        String template = configService.getString(configKey);
        if (template == null || template.isBlank()) {
            template = defaultTemplate;
        }
        return PLACEHOLDER.matcher(template).replaceAll(match -> {
            String key = match.group(1);
            String value = vars == null ? null : vars.get(key);
            return value == null ? match.group(0) : java.util.regex.Matcher.quoteReplacement(value);
        });
    }
}
