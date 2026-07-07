package com.shifa.oms.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Locale;

/**
 * Web MVC conversion tweaks.
 *
 * <p>Registers a <strong>case-insensitive, separator-tolerant</strong> converter
 * for binding query/path {@code String} values to {@code enum} request
 * parameters. The Angular {@code core} models send lifecycle values in
 * Title_Case (e.g. {@code Delivered}, {@code Out_For_Delivery},
 * {@code Pending_Admin_Approval}) while the backend enum constants are UPPERCASE
 * ({@code DELIVERED}, {@code OUT_FOR_DELIVERY}, {@code PENDING_ADMIN_APPROVAL}).
 * Spring's default enum binding is case-sensitive, so a status filter like
 * {@code ?status=Delivered} would otherwise fail conversion and surface as a
 * 500. This converter normalises the incoming value (trim, spaces/hyphens →
 * underscore, upper-case) before {@link Enum#valueOf}, so all reasonable casings
 * map to the correct constant. An unknown value still throws, which the
 * {@code GlobalExceptionHandler} renders as a 400 (not a 500).
 */
@Configuration
public class WebConversionConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(@NonNull FormatterRegistry registry) {
        registry.addConverterFactory(new CaseInsensitiveEnumConverterFactory());
    }

    /** Produces a case-insensitive {@code String → Enum} converter for any enum type. */
    @SuppressWarnings("rawtypes")
    static final class CaseInsensitiveEnumConverterFactory implements ConverterFactory<String, Enum> {
        @Override
        @NonNull
        public <T extends Enum> Converter<String, T> getConverter(@NonNull Class<T> targetType) {
            return new StringToCaseInsensitiveEnum<>(targetType);
        }
    }

    /** Normalises a String and resolves it to an enum constant of {@code enumType}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class StringToCaseInsensitiveEnum<T extends Enum> implements Converter<String, T> {

        private final Class<T> enumType;

        StringToCaseInsensitiveEnum(Class<T> enumType) {
            this.enumType = enumType;
        }

        @Override
        public T convert(@NonNull String source) {
            String value = source.trim();
            if (value.isEmpty()) {
                return null;
            }
            String normalized = value.replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
            return (T) Enum.valueOf((Class) this.enumType, normalized);
        }
    }
}
