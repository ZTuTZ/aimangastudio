package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("export_stored_object")
public class ExportStoredObject {
    public static final String KIND_CHECKPOINT = "CHECKPOINT";
    public static final String KIND_FINAL = "FINAL";
    public static final String STATE_UPLOADING = "UPLOADING";
    public static final String STATE_RETAINED = "RETAINED";
    public static final String STATE_DELETE_PENDING = "DELETE_PENDING";
    public static final String STATE_DELETING = "DELETING";
    public static final String STATE_DELETED = "DELETED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long planUnitId;
    private Long artifactId;
    private Long generation;
    private String kind;
    private String storageKey;
    private String storageUrl;
    private String state;
    private Long byteSize;
    private String sha256;
    private String ownerToken;
    private LocalDateTime uploadDeadline;
    private LocalDateTime deleteAfter;
    private String cleanupToken;
    private LocalDateTime cleanupLeaseUntil;
    private Integer retryCount;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
