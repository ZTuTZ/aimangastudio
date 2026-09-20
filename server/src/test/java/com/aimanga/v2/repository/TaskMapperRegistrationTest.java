package com.aimanga.v2.repository;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Update;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class TaskMapperRegistrationTest {

    @Test
    void registersTaskMapperWithItsCountAndPauseStatements() {
        assertThatCode(() -> new Configuration().addMapper(TaskMapper.class))
                .doesNotThrowAnyException();
    }

    @Test
    void claimSqlRejectsTaskAndProjectPauseIntent() throws Exception {
        Update update = TaskMapper.class
                .getMethod("claim", Long.class, String.class, String.class, int.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).toLowerCase();

        assertThat(sql).contains("pause_requested = 0");
        assertThat(sql).contains("project");
        assertThat(sql).contains("plan_initialized_at is not null");
    }

    @Test
    void drainCompletionRechecksLatestTaskAndProjectPauseIntent() throws Exception {
        Update update = TaskMapper.class
                .getMethod("pauseTask", Long.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).toLowerCase();

        assertThat(sql).contains("pause_requested");
        assertThat(sql).contains("project");
        assertThat(sql).contains("then 7 else 0");
    }

    @Test
    void projectPauseOnlyMovesPendingAndProjectResumeSkipsManualPauses() throws Exception {
        Update pause = TaskMapper.class.getMethod("pausePendingForProject", Long.class).getAnnotation(Update.class);
        Update resume = TaskMapper.class.getMethod("resumeProjectPausedTask", Long.class).getAnnotation(Update.class);

        assertThat(String.join(" ", pause.value()).toLowerCase()).contains("status = 0");
        assertThat(String.join(" ", resume.value()).toLowerCase())
                .contains("status = 7")
                .contains("pause_requested = 0");
    }

    @Test
    void taskResumeCannotBypassConcurrentProjectPause() throws Exception {
        Update resume = TaskMapper.class.getMethod("resumeTask", Long.class).getAnnotation(Update.class);
        Update cancelDrain = TaskMapper.class.getMethod("cancelPauseRunning", Long.class).getAnnotation(Update.class);

        assertThat(String.join(" ", resume.value()).toLowerCase())
                .contains("not exists")
                .contains("project")
                .contains("pause_requested = 1");
        assertThat(String.join(" ", cancelDrain.value()).toLowerCase())
                .contains("not exists")
                .contains("project")
                .contains("pause_requested = 1");
    }

    @Test
    void permitLossRevokesTheExactTaskClaimBeforeRequeue() throws Exception {
        Update update = TaskMapper.class
                .getMethod("requeueAfterPermitLoss", Long.class, String.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).toLowerCase();

        assertThat(sql).contains("status = 0")
                .contains("claim_token = null")
                .contains("claim_token = #{token}")
                .contains("status = 1");
    }

    @Test
    void pageReplacementCancelsHistoricalSuccessfulPlanUnits() throws Exception {
        Update update = TaskPlanUnitMapper.class
                .getMethod("cancelPageUnits", java.util.List.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql).contains("status in (0,2,3)");
    }
}
