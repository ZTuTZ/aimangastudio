package com.aimanga.v2.storage;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.service.ConfigService;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 阿里云 OSS 存储:对象键 ASCII 化 {dir}/{userId}/{yyyyMMdd}/{ts}_{rand}.{ext};
 * 支持字节数组与远程 URL 下载转存;未配置凭证时抛出明确业务错误。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssStorageService implements StorageService {

    private static final Set<String> ALLOWED_EXTS = Set.of("png", "jpg", "jpeg", "webp", "gif", "avif", "bmp");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Random RANDOM = new Random();

    private final ConfigService configService;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile OSS client;
    private volatile String clientKey = "";

    @Override
    public boolean isConfigured() {
        return !configService.getString("oss_access_key").isBlank()
                && !configService.getString("oss_access_secret").isBlank()
                && !configService.getString("oss_bucket").isBlank();
    }

    @Override
    public String saveImage(String dir, Long userId, byte[] data, String ext) {
        OSS oss = client();
        String key = buildKey(dir, userId, ext);
        oss.putObject(bucket(), key, new java.io.ByteArrayInputStream(data));
        String url = publicUrl(key);
        log.info("[oss] 上传 {} bytes → {}", data.length, url);
        return url;
    }

    @Override
    public String saveImageFromUrl(String dir, Long userId, String url) {
        // 已属于本 bucket 的 URL 直接返回,避免重复上传
        String host = hostPrefix();
        if (url != null && url.startsWith(host)) {
            return url;
        }
        byte[] data = download(url);
        String ext = extFromUrlOrData(url, data);
        return saveImage(dir, userId, data, ext);
    }

    @Override
    public String thumbnail(String url) {
        String process = configService.getString("oss_image_process");
        if (url == null || process == null || process.isBlank()) {
            return url;
        }
        if (isConfigured() && url.startsWith(hostPrefix()) && !url.contains("x-oss-process=")) {
            return url + (url.contains("?") ? "&" : "?") + process;
        }
        return url;
    }

    private OSS client() {
        String accessKey = configService.getString("oss_access_key");
        String accessSecret = configService.getString("oss_access_secret");
        String bucket = configService.getString("oss_bucket");
        if (accessKey.isBlank() || accessSecret.isBlank() || bucket.isBlank()) {
            throw new BusinessException(400, "请先在系统配置中填写 OSS 参数(oss_access_key / oss_access_secret / oss_bucket)");
        }
        String key = accessKey + ":" + accessSecret + ":" + bucket + ":" + endpoint();
        if (client == null || !key.equals(clientKey)) {
            synchronized (this) {
                if (client == null || !key.equals(clientKey)) {
                    if (client != null) {
                        client.shutdown();
                    }
                    client = new OSSClientBuilder().build(endpoint(), accessKey, accessSecret);
                    clientKey = key;
                    log.info("[oss] 客户端已构建 bucket={} endpoint={}", bucket, endpoint());
                }
            }
        }
        return client;
    }

    private String bucket() {
        return configService.getString("oss_bucket");
    }

    private String endpoint() {
        String endpoint = configService.getString("oss_endpoint");
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint.startsWith("http") ? endpoint : "https://" + endpoint;
        }
        String region = configService.getString("oss_region");
        return "https://" + (region == null || region.isBlank() ? "oss-cn-hangzhou" : region) + ".aliyuncs.com";
    }

    private String hostPrefix() {
        String bucket = bucket();
        String endpoint = endpoint().replaceFirst("^https?://", "");
        return "https://" + bucket + "." + endpoint + "/";
    }

    private String publicUrl(String key) {
        return hostPrefix() + key;
    }

    private String buildKey(String dir, Long userId, String ext) {
        String safeExt = ext == null ? "png" : ext.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (!ALLOWED_EXTS.contains(safeExt)) {
            safeExt = "png";
        }
        String day = LocalDateTime.now().format(DAY);
        String name = System.currentTimeMillis() + "_" + Integer.toHexString(RANDOM.nextInt(0x10000))
                + "." + safeExt;
        return (dir == null ? "misc" : dir) + "/" + (userId == null ? 0 : userId) + "/" + day + "/" + name;
    }

    private byte[] download(String url) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            throw new BusinessException(400, "无效的图片地址: " + url);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new BusinessException(502, "图片下载失败 HTTP " + response.statusCode() + ": " + url);
            }
            if (response.body() == null || response.body().length == 0) {
                throw new BusinessException(502, "图片下载内容为空: " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new BusinessException(502, "图片下载失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(502, "图片下载被中断");
        }
    }

    private String extFromUrlOrData(String url, byte[] data) {
        String lower = url == null ? "" : url.toLowerCase();
        int q = lower.indexOf('?');
        String path = q > 0 ? lower.substring(0, q) : lower;
        for (String ext : ALLOWED_EXTS) {
            if (path.endsWith("." + ext)) {
                return "jpeg".equals(ext) ? "jpg" : ext;
            }
        }
        // 从 magic bytes 猜测
        if (data.length > 3) {
            if ((data[0] & 0xFF) == 0x89 && data[1] == 'P') return "png";
            if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return "jpg";
            if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') return "webp";
            if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "gif";
        }
        return "png";
    }
}
