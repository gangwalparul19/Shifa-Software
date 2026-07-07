package com.shifa.oms.notification;

import java.util.List;
import java.util.Optional;

/**
 * A fully-resolved WhatsApp message ready to send: the recipient, the
 * pre-approved template name, and the ordered, resolved template parameters
 * (Req 14.1, 14.2, 14.3).
 *
 * <p>This is the message-model tested by Property 20 and stored (as a payload)
 * on the {@code WHATSAPP_NOTIFY} outbox event, so the drainer can send it
 * without re-loading the order aggregate. The {@link Param} order mirrors the
 * template's parameter mapping.
 *
 * @param recipientMobile the customer mobile the message is addressed to
 * @param templateName    the pre-approved Meta template name
 * @param parameters      the ordered, resolved template parameters
 */
public record WhatsAppMessage(String recipientMobile, String templateName, List<Param> parameters) {

    public WhatsAppMessage {
        parameters = List.copyOf(parameters);
    }

    /** A single resolved template parameter (name → value). */
    public record Param(String name, String value) {
    }

    /** Whether a parameter with the given name is present. */
    public boolean hasParameter(String name) {
        return parameters.stream().anyMatch(p -> p.name().equals(name));
    }

    /** The value of a named parameter, if present. */
    public Optional<String> parameterValue(String name) {
        return parameters.stream()
                .filter(p -> p.name().equals(name))
                .map(Param::value)
                .findFirst();
    }
}
