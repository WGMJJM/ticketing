package com.ticketing.auth;

public record UserView(String id, String username, String role) {
    public static UserView from(UserAccount account) {
        return new UserView(account.getId().toString(), account.getUsername(), account.getRole());
    }

    public static UserView from(AccountPrincipal principal) {
        return new UserView(principal.getId().toString(), principal.getUsername(), principal.getRole());
    }
}
