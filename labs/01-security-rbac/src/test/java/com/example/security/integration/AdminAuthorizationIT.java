package com.example.security.integration;

import com.example.security.api.AdminController;
import com.example.security.application.RbacService;
import com.example.security.domain.User;
import com.example.security.domain.UserRepository;
import com.example.security.infrastructure.security.JwtAuthenticationFilter;
import com.example.security.infrastructure.security.JwtTokenService;
import com.example.security.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;

@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class AdminAuthorizationIT {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtTokenService tokens;

    @MockitoBean
    private RbacService rbac;

    @MockitoBean
    private UserRepository users;

    @Test
    void returns401WhenAccessTokenIsMissing() throws Exception {
        mvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns403WhenAuthenticatedUserLacksPermission() throws Exception {
        when(tokens.verifyAccessToken("valid-user-token"))
                .thenReturn(new JwtTokenService.AccessPrincipal(1L, "student"));
        when(rbac.authoritiesOf(1L)).thenReturn(Set.of());

        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer valid-user-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsUsersWhenAuthenticatedUserHasPermission() throws Exception {
        when(tokens.verifyAccessToken("valid-admin-token"))
                .thenReturn(new JwtTokenService.AccessPrincipal(2L, "admin"));
        when(rbac.authoritiesOf(2L)).thenReturn(Set.of("system:user:read"));
        when(users.findAll()).thenReturn(java.util.List.of(
                new User(1L, "student", "hidden-hash", true)
        ));

        mvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer valid-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].username").value("student"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void grantsRoleWhenAuthenticatedUserHasGrantPermission() throws Exception {
        when(tokens.verifyAccessToken("valid-super-admin-token"))
                .thenReturn(new JwtTokenService.AccessPrincipal(3L, "super-admin"));
        when(rbac.authoritiesOf(3L)).thenReturn(Set.of("system:user:grant"));

        mvc.perform(post("/api/admin/users/1/roles/10")
                        .header("Authorization", "Bearer valid-super-admin-token"))
                .andExpect(status().isNoContent());

        verify(rbac).grantRole(1L, 10L);
    }
}
