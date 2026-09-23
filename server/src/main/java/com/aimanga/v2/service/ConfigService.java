package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.SystemConfig;
import com.aimanga.v2.repository.SystemConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统配置:进程内缓存 + 保存即失效(热更新);密钥类键读取时脱敏。
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    /** 值为密钥的键名片段(脱敏展示) */
    private static final Set<String> SECRET_MARKERS = Set.of("api_key", "access_secret", "password");
    private static final Set<String> POSITIVE_LONG_KEYS = Set.of(
            "export_max_bytes", "export_temp_max_bytes", "export_checkpoint_ttl_hours",
            "export_download_max_seconds", "export_upload_timeout_seconds");

    private final SystemConfigMapper mapper;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String getString(String key) {
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        reload();
        return cache.getOrDefault(key, "");
    }

    public int getInt(String key, int defaultValue) {
        String value = getString(key);
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** 读取可能超过 Integer 范围的正整数配置；已配置的非法值必须显式报错。 */
    public long getLong(String key, long defaultValue) {
        String value = getString(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(500, "系统配置 " + key + " 必须是正整数");
        }
    }

    /** 全量键值(未脱敏,仅后端内部使用) */
    public Map<String, String> getMap() {
        reload();
        return new LinkedHashMap<>(cache);
    }

    /** 指定分组的键值 */
    public Map<String, String> getGroup(String group) {
        Map<String, String> result = new LinkedHashMap<>();
        for (SystemConfig config : listAll()) {
            if (group.equals(config.getConfigGroup())) {
                result.put(config.getConfigKey(), config.getConfigValue() == null ? "" : config.getConfigValue());
            }
        }
        return result;
    }

    /** 全量键值(密钥脱敏:已配置 → ***,未配置 → 空串) */
    public Map<String, String> toMaskedMap() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : getMap().entrySet()) {
            result.put(entry.getKey(), isSecretKey(entry.getKey()) && !entry.getValue().isBlank() ? "***" : entry.getValue());
        }
        return result;
    }

    /** 批量保存(热更新);密钥类键的空串与 *** 视为"不修改" */
    public void save(Map<String, String> configs) {
        if (configs == null) {
            return;
        }
        validate(configs);
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (isSecretKey(key) && (value.isBlank() || "***".equals(value))) {
                continue;
            }
            SystemConfig existing = mapper.selectOne(new LambdaQueryWrapper<SystemConfig>()
                    .eq(SystemConfig::getConfigKey, key));
            if (existing == null) {
                SystemConfig config = new SystemConfig();
                config.setConfigKey(key);
                config.setConfigValue(value);
                config.setConfigGroup("default");
                mapper.insert(config);
            } else {
                SystemConfig patch = new SystemConfig();
                patch.setId(existing.getId());
                patch.setConfigValue(value);
                mapper.updateById(patch);
            }
            cache.put(key, value);
        }
    }

    private void validate(Map<String, String> configs) {
        for (String key : POSITIVE_LONG_KEYS) {
            if (!configs.containsKey(key)) {
                continue;
            }
            String value = configs.get(key);
            try {
                if (value == null || Long.parseLong(value.trim()) <= 0) {
                    throw new NumberFormatException("not positive");
                }
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "配置项 " + key + " 必须是正整数");
            }
        }
    }

    public boolean isSecretKey(String key) {
        String lower = key.toLowerCase();
        return SECRET_MARKERS.stream().anyMatch(lower::contains);
    }

    private List<SystemConfig> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<SystemConfig>().orderByAsc(SystemConfig::getId));
    }

    private synchronized void reload() {
        cache.clear();
        for (SystemConfig config : listAll()) {
            cache.put(config.getConfigKey(), config.getConfigValue() == null ? "" : config.getConfigValue());
        }
    }
}
