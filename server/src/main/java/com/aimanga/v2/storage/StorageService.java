package com.aimanga.v2.storage;

/**
 * 存储抽象:所有图片统一转存,返回可公开访问的 URL(当前实现:阿里云 OSS)。
 */
public interface StorageService {

    /** 上传字节流,返回可访问 URL */
    String saveImage(String dir, Long userId, byte[] data, String ext);

    /** 下载远程图片并转存,返回可访问 URL */
    String saveImageFromUrl(String dir, Long userId, String url);

    /** 是否已配置(未配置时上传应返回明确报错) */
    boolean isConfigured();

    /** 对已转存的 OSS URL 附加缩略处理参数(未命中返回原 URL) */
    String thumbnail(String url);
}
