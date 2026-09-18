package com.aimanga.v2.storage;

import com.aimanga.v2.common.BusinessException;
import com.aimanga.v2.pipeline.RemoteImageFetcher;
import com.aimanga.v2.service.ConfigService;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
public class OssStorageService implements StorageService {

    private static final Set<String> ALLOWED_EXTS = Set.of("png", "jpg", "jpeg", "webp", "gif", "avif", "bmp");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Random RANDOM = new Random();

    private final ConfigService configService;
    private final RemoteImageFetcher remoteImageFetcher;

    private volatile OSS client;
    private volatile String clientKey = "";

    @Autowired
    public OssStorageService(ConfigService configService, RemoteImageFetcher remoteImageFetcher) {
        this.configService = configService;
        this.remoteImageFetcher = remoteImageFetcher;
    }

    /** 便于非 Spring 的单元测试构造；生产环境始终注入同一个安全拉取器。 */
    public OssStorageService(ConfigService configService) {
        this(configService, new RemoteImageFetcher(configService));
    }

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
        try (RemoteImageFetcher.FetchResult fetch = remoteImageFetcher.fetchStream(url)) {
            String key = buildKey(dir, userId, extFromMimeOrUrl(fetch.mime(), url));
            client().putObject(bucket(), key, fetch.inputStream());
            return publicUrl(key);
        }
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

    private String extFromMimeOrUrl(String mime, String url) {
        if (mime != null) {
            return switch (mime) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/webp" -> "webp";
                case "image/gif" -> "gif";
                case "image/avif" -> "avif";
                default -> "png";
            };
        }
        String lower = url == null ? "" : url.toLowerCase();
        int q = lower.indexOf('?');
        String path = q > 0 ? lower.substring(0, q) : lower;
        for (String ext : ALLOWED_EXTS) {
            if (path.endsWith("." + ext)) {
                return "jpeg".equals(ext) ? "jpg" : ext;
            }
        }
        return "png";
    }
}
