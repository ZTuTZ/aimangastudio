package com.aimanga.v2.service;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.model.Project;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 故事原文导入:TXT(UTF-8/GBK 自动探测)/ DOCX(POI)→ 每个文件创建一部作品。
 * Phase 5 将在此处按 feature_auto_split 开关自动入队拆话任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final ProjectService projectService;

    public List<Project> importFiles(List<MultipartFile> files, String aspectRatio, String colorMode, Long stylePresetId) {
        List<Project> created = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename() == null ? "未命名作品" : file.getOriginalFilename();
            try {
                String text = extractText(file);
                if (text.isBlank()) {
                    errors.add(filename + ": 文件内容为空");
                    continue;
                }
                String title = stripExt(filename);
                created.add(projectService.create(title, text, aspectRatio, colorMode, stylePresetId));
            } catch (BusinessException e) {
                errors.add(filename + ": " + e.getMessage());
            } catch (Exception e) {
                log.warn("[import] 解析失败 {}", filename, e);
                errors.add(filename + ": 解析失败(" + e.getMessage() + ")");
            }
        }
        if (created.isEmpty() && !errors.isEmpty()) {
            throw new BusinessException(400, String.join("; ", errors));
        }
        for (String error : errors) {
            log.warn("[import] 部分文件导入失败: {}", error);
        }
        return created;
    }

    private String extractText(MultipartFile file) {
        String filename = (file.getOriginalFilename() == null ? "" : file.getOriginalFilename()).toLowerCase();
        try {
            if (filename.endsWith(".docx")) {
                try (XWPFDocument document = new XWPFDocument(file.getInputStream());
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    return extractor.getText().trim();
                }
            }
            // 默认按 TXT 处理(UTF-8 优先,失败回退 GBK)
            byte[] bytes = file.getBytes();
            return decodeText(bytes);
        } catch (IOException e) {
            throw new BusinessException(400, "文件读取失败: " + e.getMessage());
        }
    }

    static String decodeText(byte[] bytes) {
        // 去 BOM
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
