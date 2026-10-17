package com.ticketing.checkin.dto;

public record TicketCredentialView(String ticketId, String credential) {
    @Override
    public String toString() { return "TicketCredentialView[ticketId=" + ticketId + ", credential=已隐藏]"; }
}
