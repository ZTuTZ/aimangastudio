package com.aimanga.v2.controller;

import com.aimanga.v2.common.Result;
import com.aimanga.v2.model.StylePreset;
import com.aimanga.v2.repository.StylePresetMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 风格预设:所有登录用户可读(启用中的);管理端 CRUD 在 T7.1 */
@RestController
@RequestMapping("/api/presets")
@RequiredArgsConstructor
public class PresetController {

    private final StylePresetMapper presetMapper;

    public record PresetVO(Long id, String name, String positivePrompt, String colorMode, String remark) {
    }

    @GetMapping
    public Result<List<PresetVO>> list() {
        List<PresetVO> presets = presetMapper.selectList(new LambdaQueryWrapper<StylePreset>()
                        .eq(StylePreset::getStatus, 1)
                        .orderByAsc(StylePreset::getSort))
                .stream()
                .map(p -> new PresetVO(p.getId(), p.getName(), p.getPositivePrompt(), p.getColorMode(), p.getRemark()))
                .toList();
        return Result.ok(presets);
    }
}
