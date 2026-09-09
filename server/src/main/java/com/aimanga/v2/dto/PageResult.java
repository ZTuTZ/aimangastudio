package com.aimanga.v2.dto;

import java.util.List;

/** 通用分页结果(records = 当前页数据, total = 总数) */
public record PageResult<T>(List<T> records, long total) {
}
