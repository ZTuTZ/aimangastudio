package com.aimanga.v2.pipeline;

import com.aimanga.v2.common.BusinessException;

/** A paid result was produced from stale page input and must not alter visible page state. */
public class ContentVersionConflictException extends BusinessException {
    public ContentVersionConflictException(String message) {
        super(409, message);
        com.aimanga.v2.task.OperationalMetrics.recordVersionConflict();
    }
}
