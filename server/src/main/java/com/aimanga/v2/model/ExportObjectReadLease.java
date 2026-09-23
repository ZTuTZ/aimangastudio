package com.aimanga.v2.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("export_object_read_lease")
public class ExportObjectReadLease {
    @TableId
    private String token;
    private Long objectId;
    private LocalDateTime leaseUntil;
    private LocalDateTime createTime;
}
