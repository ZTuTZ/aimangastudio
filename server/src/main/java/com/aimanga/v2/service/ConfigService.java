package com.aimanga.v2.service;

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
