package com.ticketing.security;

import com.ticketing.exception.BusinessException;
import com.ticketing.vo.AccountSessionView;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

@Component
public class AccountSessions {
    private final SessionRegistry registry;

    public AccountSessions(SessionRegistry registry) { this.registry = registry; }

    public List<AccountSessionView> list(AccountPrincipal owner, String currentId) {
        return active(owner).stream()
                .map(session -> new AccountSessionView(handle(session), session.getSessionId().equals(currentId),
                        session.getLastRequest().toInstant()))
                .sorted(Comparator.comparing(AccountSessionView::current).reversed()
                        .thenComparing(AccountSessionView::lastActiveAt, Comparator.reverseOrder()))
                .toList();
    }

    public void revoke(AccountPrincipal owner, String currentId, String handle) {
        SessionInformation session = active(owner).stream()
                .filter(candidate -> handle(candidate).equals(handle))
                .findFirst().orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "该登录会话已结束或不存在"));
        if (session.getSessionId().equals(currentId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CURRENT_SESSION", "请使用退出登录结束当前会话");
        }
        session.expireNow();
    }

    public void revokeOthers(AccountPrincipal owner, String currentId) {
        active(owner).stream().filter(session -> !session.getSessionId().equals(currentId))
                .forEach(SessionInformation::expireNow);
    }

    public void revokeCredential(AccountPrincipal previous) {
        active(previous).forEach(SessionInformation::expireNow);
    }

    private List<SessionInformation> active(AccountPrincipal owner) {
        return registry.getAllSessions(owner, false).stream()
                .filter(session -> session.getPrincipal() instanceof AccountPrincipal principal
                        && owner.getId().equals(principal.getId())
                        && owner.credentialStamp().equals(principal.credentialStamp()))
                .toList();
    }

    private String handle(SessionInformation session) {
        return AccountPrincipal.digest("session\0" + session.getSessionId());
    }
}
