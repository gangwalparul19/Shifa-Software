package com.shifa.oms.chatbot.domain;

/** A configured bank / UPI QR account the chatbot can resolve by name ("Shifa QR 1", "Bank 2"). */
public record AccountRef(long id, String name) {
}
