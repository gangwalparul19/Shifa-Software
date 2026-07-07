package com.shifa.oms.packing;

import com.shifa.oms.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a scanned barcode does not match any order (Req 11.3). Mapped to
 * HTTP 404 with the stable code {@code BARCODE_NOT_RECOGNIZED} so the packing UI
 * can show a clear "barcode not recognized" message rather than a generic error.
 */
public class BarcodeNotRecognizedException extends ApiException {

    public BarcodeNotRecognizedException(String barcode) {
        super(HttpStatus.NOT_FOUND, "BARCODE_NOT_RECOGNIZED",
                "Barcode '" + barcode + "' is not recognized; no matching order exists.");
    }
}
