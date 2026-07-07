package com.shifa.oms.auth;

/** Distinguishes short-lived access tokens from longer-lived refresh tokens. */
public enum TokenType {
    ACCESS,
    REFRESH
}
