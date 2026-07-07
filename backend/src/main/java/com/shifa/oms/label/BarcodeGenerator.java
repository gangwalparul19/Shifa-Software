package com.shifa.oms.label;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.shifa.oms.common.ApiException;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Generates a scannable <strong>Code128</strong> barcode encoding the order code
 * and renders it to a PNG image (design "PDF / label / barcode generation"; the
 * barcode is embedded into the label PDF by {@link LabelPdfRenderer}).
 *
 * <p>Uses ZXing's {@link Code128Writer} to build the barcode matrix and
 * {@link MatrixToImageWriter} to serialize it as PNG bytes. Kept as a small,
 * dependency-light component so both the label service and tests can produce a
 * barcode without any PDF context.
 */
public class BarcodeGenerator {

    private static final int DEFAULT_WIDTH = 480;
    private static final int DEFAULT_HEIGHT = 120;

    /**
     * Encodes {@code value} as a Code128 barcode PNG.
     *
     * @param value the string to encode (typically the order code); non-blank
     * @return PNG image bytes of the barcode
     */
    public byte[] code128Png(String value) {
        return code128Png(value, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Encodes {@code value} as a Code128 barcode PNG at the given dimensions.
     *
     * @param value  the string to encode; non-blank
     * @param width  the barcode image width in pixels
     * @param height the barcode image height in pixels
     * @return PNG image bytes of the barcode
     */
    public byte[] code128Png(String value, int width, int height) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BARCODE_VALUE",
                    "A non-blank value is required to generate a barcode.");
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 10);
        try {
            BitMatrix matrix = new Code128Writer().encode(value, BarcodeFormat.CODE_128, width, height, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (IOException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "BARCODE_GENERATION_FAILED",
                    "Failed to generate the barcode for value " + value + ".");
        }
    }
}
