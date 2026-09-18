package com.aimanga.v2.repository;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class TaskMapperRegistrationTest {

    @Test
    void registersTaskMapperWithItsCountAndPauseStatements() {
        assertThatCode(() -> new Configuration().addMapper(TaskMapper.class))
                .doesNotThrowAnyException();
    }
}
