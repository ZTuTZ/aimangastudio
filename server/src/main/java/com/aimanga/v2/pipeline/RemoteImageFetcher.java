package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 远程图片拉取器(Phase 8.8):
 * - 仅 http/https;
 * - 禁 localhost/loopback/link-local/RFC1918 私网(SSRF 防护);
 * - redirect 后重新校验目标(最多 5 次);
 * - Content-Length 与流式读取计数双重限制(remote_image_max_bytes,默认 30MB);
 * - MIME 白名单:image/png, image/jpeg, image/webp, image/gif, image/avif;
 * - 流式返回 InputStream,调用方负责关闭,不将全图读入 byte[]。
 */
@Slf4j
@Component
public class RemoteImageFetcher {

    private static final List<String> ALLOWED_MIME = List.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/avif");
    private static final int MAX_REDIRECTS = 5;

    private final com.aimanga.v2.service.ConfigService configService;

    public RemoteImageFetcher(com.aimanga.v2.service.ConfigService configService) {
        this.configService = configService;
    }

    private long maxBytes() {
        return configService.getInt("remote_image_max_bytes", 31_457_280);
    }

    /** 流式拉取远程图片,返回已校验的 InputStream(调用方负责关闭) */
    public FetchResult fetchStream(String url) {
        String current = url;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URI uri = URI.create(current);
            validateUri(uri);
            HttpURLConnection conn = openConnection(uri);
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null || location.isBlank()) {
                        throw new BusinessException(502, "图片重定向缺少 Location: " + url);
                    }
                    current = uri.resolve(location).toString();
                    continue; // redirect 后重新 validateUri(SSRF 防护)
                }
                if (code != 200) {
                    throw new BusinessException(502, "拉取图片失败(HTTP " + code + "): " + url);
                }
                String mime = conn.getContentType();
                if (mime != null) {
                    mime = mime.split(";")[0].trim().toLowerCase();
                    if (!ALLOWED_MIME.contains(mime)) {
                        throw new BusinessException(502, "不支持的图片 MIME: " + mime);
                    }
                }
                long declared = conn.getContentLengthLong();
                if (declared > maxBytes()) {
                    throw new BusinessException(502, "图片超过大小限制(" + maxBytes() / 1024 / 1024 + "MB): " + url);
                }
                InputStream in = conn.getInputStream();
                return new FetchResult(new CountingLimitedInputStream(in, maxBytes()), mime);
            } catch (BusinessException e) {
                conn.disconnect();
                throw e;
            } catch (IOException e) {
                conn.disconnect();
                throw new BusinessException(502, "拉取图片 IO 失败: " + url + " (" + e.getMessage() + ")");
            }
        }
        throw new BusinessException(502, "重定向次数超限: " + url);
    }

    private void validateUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BusinessException(502, "仅允许 http/https 图片地址: " + uri);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(502, "图片地址缺少 host: " + uri);
        }
        // CDN/OSS 白名单只限制域名集合，不豁免 DNS 解析后的内网地址校验。
        String allowList = configService.getString("remote_image_allow_hosts");
        if (allowList != null && !allowList.isBlank()) {
            boolean allowed = false;
            for (String candidate : allowList.split(",")) {
                if (host.equals(candidate.trim()) || host.endsWith("." + candidate.trim())) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new BusinessException(502, "图片 host 不在白名单: " + host);
            }
        }
        // DNS 解析后校验 IP(禁 localhost/loopback/link-local/私网)
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isBlockedAddress(addr)) {
                    throw new BusinessException(502, "禁止访问内网图片地址: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new BusinessException(502, "图片 host 无法解析: " + host);
        }
    }

    private HttpURLConnection openConnection(URI uri) {
        try {
            return (HttpURLConnection) uri.toURL().openConnection();
        } catch (IOException e) {
            throw new BusinessException(502, "打开图片连接失败: " + uri + " (" + e.getMessage() + ")");
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 unique-local FC00::/7，相当于 IPv4 RFC1918 私网。
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** 拉取结果:流式 InputStream + MIME(实现 AutoCloseable 供 try-with-resources) */
    public record FetchResult(InputStream inputStream, String mime) implements AutoCloseable {
        @Override
        public void close() {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 计数限流流(读取超过 max 立即中止) */
    static class CountingLimitedInputStream extends java.io.FilterInputStream {
        private final long max;
        private long read;

        CountingLimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            if (++read > max) throw new IOException("图片超过大小限制");
            return super.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                read += n;
                if (read > max) throw new IOException("图片超过大小限制");
            }
            return n;
        }
    }
}
