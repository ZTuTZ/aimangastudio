package com.aimanga.v2.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusTest {

    @Test
    void pausedTaskStillOccupiesItsExecutionSlot() {
        assertThat(TaskStatus.active(TaskStatus.PAUSED)).isTrue();
        assertThat(TaskStatus.terminal(TaskStatus.PAUSED)).isFalse();
        assertThat(TaskStatus.resumable(TaskStatus.PAUSED)).isTrue();
    }
}
