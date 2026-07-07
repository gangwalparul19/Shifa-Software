package com.shifa.oms.notification;

import java.util.List;

/**
 * A pre-approved Meta WhatsApp message template and its ordered parameter
 * mapping (Req 14.3).
 *
 * <p>Only templates registered in the {@link WhatsAppTemplateRegistry} may be
 * sent, so every outgoing message references a template Meta has approved. The
 * {@code parameterKeys} declare, in order, the named substitution parameters the
 * template expects; the {@link WhatsAppMessageFactory} resolves each key to a
 * value from the order/tracking context when it builds a concrete message.
 *
 * @param event         the lifecycle event this template serves
 * @param templateName  the pre-approved Meta template name
 * @param parameterKeys the ordered parameter keys the template accepts
 */
public record WhatsAppTemplate(NotificationEvent event, String templateName, List<String> parameterKeys) {

    public WhatsAppTemplate {
        parameterKeys = List.copyOf(parameterKeys);
    }
}
