package com.aimanga.v2.config;

import com.aimanga.v2.service.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    @Test
    void doesNotCreateAnAdministratorWithoutExplicitCredentials() throws Exception {
        UserService userService = mock(UserService.class);
        when(userService.list()).thenReturn(List.of());

        new AdminBootstrap(userService).run(null);

        verify(userService, never()).createUser(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
