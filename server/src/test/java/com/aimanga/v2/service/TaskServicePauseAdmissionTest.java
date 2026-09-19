package com.aimanga.v2.service;

import com.aimanga.v2.model.Project;
import com.aimanga.v2.task.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskServicePauseAdmissionTest {

    @Test
    void projectPauseAdmitsNewTaskAsPaused() {
        Project project = new Project();
        project.setPauseRequested(true);

        assertThat(TaskService.initialStatus(project)).isEqualTo(TaskStatus.PAUSED);
    }

    @Test
    void activeProjectAdmitsNewTaskAsPending() {
        Project project = new Project();
        project.setPauseRequested(false);

        assertThat(TaskService.initialStatus(project)).isEqualTo(TaskStatus.PENDING);
    }
}
