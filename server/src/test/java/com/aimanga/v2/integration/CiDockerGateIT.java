package com.aimanga.v2.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Fail the GitHub backend check if Docker-backed integration tests would be skipped. */
class CiDockerGateIT {
    @Test
    @EnabledIfEnvironmentVariable(named = "GITHUB_ACTIONS", matches = "true")
    void githubRunnerProvidesDockerForIntegrationTests() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("GitHub Actions must provide Docker for MySQL/Redis integration tests")
                .isTrue();
    }
}
