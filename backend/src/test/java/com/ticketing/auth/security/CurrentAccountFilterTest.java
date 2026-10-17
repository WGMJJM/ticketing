package com.ticketing.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.entity.UserAccount;
import com.ticketing.auth.mapper.UserAccountMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CurrentAccountFilterTest {
    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "filter-test"), UserAccount.class);
    }
    private final UserAccountMapper accounts = mock(UserAccountMapper.class);
    private final SecurityContextRepository contexts = mock(SecurityContextRepository.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final CurrentAccountFilter filter = new CurrentAccountFilter(accounts,
            new OrRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher("/api/v1/admin/**"),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/v1/orders/**"),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/v1/waitlist/**"),
                    PathPatternRequestMatcher.withDefaults().matcher(org.springframework.http.HttpMethod.GET, "/api/v1/auth/me")),
            contexts, new ObjectMapper());

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/activities", "GET,/api/v1/activities/1", "GET,/api/v1/activities/1/sessions",
            "GET,/api/v1/activities/1/sessions/2/tickets", "GET,/api/v1/auth/csrf",
            "POST,/api/v1/auth/login", "POST,/api/v1/auth/register", "POST,/api/v1/auth/logout"})
    void publicAndAuthenticationRequestsNeverQueryAccountEvenWithOldIdentity(String method, String path) throws Exception {
        var identity = AccountPrincipal.forSession(account("ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, identity.getAuthorities()));
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(accounts, contexts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/me", "/api/v1/orders", "/api/v1/orders/1", "/api/v1/orders/limits", "/api/v1/waitlist", "/api/v1/waitlist/1"})
    void anonymousProtectedRequestDoesNotQueryAccount(String path) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        filter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), chain);
        verifyNoInteractions(accounts, contexts);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void unknownAuthenticatedPrincipalFailsClosed() throws Exception {
        var identity = User.withUsername("unknown").password("unused").roles("ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, identity.getAuthorities()));
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/activities");
        request.getSession();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(accounts, contexts, chain);
    }

    @Test
    void roleRefreshSavesNewContextWithoutCredentialsOrMutatingOldContext() throws Exception {
        var identity = AccountPrincipal.forSession(account("ADMIN"));
        var original = SecurityContextHolder.createEmptyContext();
        original.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, identity.getAuthorities()));
        SecurityContextHolder.setContext(original);
        when(accounts.selectOne(any())).thenReturn(account("USER"));
        var request = new MockHttpServletRequest("GET", "/api/v1/admin/activities");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        var refreshed = SecurityContextHolder.getContext();
        assertThat(refreshed).isNotSameAs(original);
        assertThat(refreshed.getAuthentication().getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(refreshed.getAuthentication().getCredentials()).isNull();
        assertThat(((AccountPrincipal) refreshed.getAuthentication().getPrincipal()).getPassword()).isNull();
        assertThat(original.getAuthentication().getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        verify(contexts).saveContext(refreshed, request, response);
        verify(chain).doFilter(request, response);
    }

    private UserAccount account(String role) {
        UserAccount account = new UserAccount();
        account.setId(1L);
        account.setUsername("test_account");
        account.setRole(role);
        account.setStatus("ACTIVE");
        return account;
    }
}
