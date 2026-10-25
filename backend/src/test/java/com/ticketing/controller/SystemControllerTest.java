package com.ticketing.controller;

import com.ticketing.config.SecurityConfig;
import com.ticketing.mapper.UserAccountMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = SystemController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import(SecurityConfig.class)
class SystemControllerTest {
    @MockitoBean
    private UserDetailsService users;
    @MockitoBean
    private UserAccountMapper accounts;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusIsPublicAndUsesContract() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.application").value("票务交易平台"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void undevelopedRoutesAreDeniedToAnonymousAndAuthenticatedUsers() throws Exception {
        mockMvc.perform(get("/api/v1/payments")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payments").with(user("测试用户"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/tickets/purchase").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void sensitiveEndpointsAndLoginPageAreNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/health/db")).andExpect(status().isForbidden());
        mockMvc.perform(get("/login")).andExpect(status().isForbidden());
    }
}
