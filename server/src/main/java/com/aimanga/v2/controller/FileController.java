package com.aimanga.v2.controller;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.common.Result;
import com.aimanga.v2.security.CurrentUser;
import com.aimanga.v2.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

/** 通用文件上传(资产参考图/风格参考图/遮罩图等),统一转存 OSS */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/avif");

    private final StorageService storageService;

    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的文件");
        }
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException(400, "仅支持图片文件(png/jpg/webp/gif)");
        }
        String ext = extOf(file.getOriginalFilename(), contentType);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(400, "文件读取失败: " + e.getMessage());
        }
        String url = storageService.saveImage("uploads", CurrentUser.id(), bytes, ext);
        return Result.ok(Map.of("url", url));
    }

    private static String extOf(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot > 0) {
                String ext = filename.substring(dot + 1).toLowerCase();
                if (!ext.isBlank()) return ext;
            }
        }
        if (contentType == null) return "png";
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }
}
