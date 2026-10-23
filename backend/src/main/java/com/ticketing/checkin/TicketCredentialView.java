package com.ticketing.checkin;

public record TicketCredentialView(String ticketId, String credential) {
    @Override
    public String toString() { return "TicketCredentialView[ticketId=" + ticketId + ", credential=已隐藏]"; }
}
