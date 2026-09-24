package com.shifa.oms.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Serializes every {@link LocalDateTime} the API returns with an explicit
 * India-Standard-Time offset (e.g. {@code 2026-09-22T18:20:43+05:30}) instead of
 * the default offset-less local string (e.g. {@code 2026-09-22T18:20:43}).
 *
 * <p>Why: the business runs entirely in India and the JVM default zone is pinned
 * to {@code Asia/Kolkata} (see {@code Application.main}), so every persisted
 * {@code LocalDateTime} (audit {@code created_at}, order {@code created_at},
 * status-history {@code changed_at}, payments, …) already holds IST wall-clock.
 * But an offset-less ISO string is interpreted by the browser in ITS OWN local
 * timezone — so a viewer outside IST (or a device set to another zone) would see
 * every timestamp shifted. Emitting the {@code +05:30} offset makes the wire
 * value unambiguous, so Angular's {@code DatePipe}/{@code new Date(...)} render
 * the correct IST instant on any device.
 *
 * <p>Deserialization is unchanged: an incoming offset-less {@code LocalDateTime}
 * (e.g. a request body's date) is still read as-is by the default JSR-310
 * deserializer — this customizer only affects OUTPUT.
 */
@Configuration
public class JacksonTimeConfig {

    /** India Standard Time — the single business timezone. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** ISO-8601 with offset, e.g. {@code 2026-09-22T18:20:43+05:30}. */
    private static final DateTimeFormatter IST_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @Bean
    Jackson2ObjectMapperBuilderCustomizer istLocalDateTimeCustomizer() {
        return builder -> builder.serializerByType(LocalDateTime.class, new IstLocalDateTimeSerializer());
    }

    /**
     * Writes a {@link LocalDateTime} (already IST wall-clock) as an ISO string
     * carrying the {@code +05:30} offset, so clients render it unambiguously.
     */
    static final class IstLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            ZoneOffset offset = IST.getRules().getOffset(value);
            gen.writeString(value.atOffset(offset).format(IST_OFFSET));
        }
    }
}
