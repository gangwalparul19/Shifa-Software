package com.shifa.oms.integration.meta.dto;

/**
 * Result of the admin Meta connectivity/access test (spec {@code meta-lead-sync},
 * Req 10). Deliberately excludes the access token (Req 10.4).
 *
 * @param ok       whether the Graph call for the configured page succeeded
 * @param pageId   the page id that was tested
 * @param pageName the page name Meta returned (null on failure)
 * @param error    the Graph error description (null on success)
 */
public record MetaConnectivityResponse(boolean ok, String pageId, String pageName, String error) {

    public static MetaConnectivityResponse success(String pageId, String pageName) {
        return new MetaConnectivityResponse(true, pageId, pageName, null);
    }

    public static MetaConnectivityResponse failure(String pageId, String error) {
        return new MetaConnectivityResponse(false, pageId, null, error);
    }
}
