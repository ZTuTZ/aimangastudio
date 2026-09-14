package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.GenerationRecord;
import com.aimanga.v2.repository.GenerationRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 生成记录服务(Phase 6.7):每次 LAYOUT/PAGE/后处理落一条完整生成上下文。
 * 用途:重绘回溯、对比模型、排查某批错误、恢复历史版本。写入失败不影响主流程(仅告警)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationRecordService {

    private final GenerationRecordMapper mapper;
    private final ObjectMapper objectMapper;

    public void record(Long projectId, Long chapterId, Long pageId, Long taskId, String kind,
                       String model, String prompt, List<String> referenceUrls, String inputUrl,
                       String resultUrl, String status, String error) {
        try {
            GenerationRecord record = new GenerationRecord();
            record.setProjectId(projectId);
            record.setChapterId(chapterId);
            record.setPageId(pageId);
            record.setTaskId(taskId);
            record.setKind(kind);
            record.setModel(model == null ? "" : model);
            record.setPrompt(prompt);
            try {
                record.setReferenceUrls(objectMapper.writeValueAsString(referenceUrls == null ? List.of() : referenceUrls));
            } catch (Exception e) {
                record.setReferenceUrls("[]");
            }
            record.setInputUrl(inputUrl);
            record.setResultUrl(resultUrl);
            record.setStatus(status == null ? GenerationRecord.STATUS_SUCCESS : status);
            record.setError(error == null ? "" : error.substring(0, Math.min(error.length(), 512)));
            record.setCreateTime(LocalDateTime.now());
            mapper.insert(record);
        } catch (Exception e) {
            log.warn("[gen-record] 记录写入失败(不影响主流程): {}", e.getMessage());
        }
    }

    /** 页的生成记录(按时间倒序) */
    public List<GenerationRecord> recordsOfPage(Long pageId) {
        return mapper.selectList(new LambdaQueryWrapper<GenerationRecord>()
                .eq(GenerationRecord::getPageId, pageId)
                .orderByDesc(GenerationRecord::getId)
                .last("LIMIT 50"));
    }
}
