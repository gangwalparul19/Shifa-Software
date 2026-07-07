package com.shifa.oms.mail;

import com.shifa.oms.mail.template.EmailModels;
import com.shifa.oms.mail.template.EmailRenderer;
import com.shifa.oms.mail.template.RenderedEmail;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Admin-only endpoint to render + send a SAMPLE of any branded email template to
 * a chosen recipient (Part 4).
 *
 * <p>This lets the operator eyeball every template in a real inbox (Gmail, when
 * {@code app.mail.mode=SMTP}) without needing real orders, or confirm the mock
 * backend "sent" it when running locally in {@code MOCK} mode. It builds
 * representative sample data for the requested type, renders it via the
 * {@link EmailRenderer}, and sends it through the configured {@link MailService}.
 */
@RestController
@RequestMapping("/api/admin/mail")
@PreAuthorize("hasRole('ADMIN')")
public class MailTestController {

    private final EmailRenderer emailRenderer;
    private final MailService mailService;

    public MailTestController(EmailRenderer emailRenderer, MailService mailService) {
        this.emailRenderer = emailRenderer;
        this.mailService = mailService;
    }

    /**
     * Renders a sample of the chosen template type and sends it to {@code to}.
     *
     * @param type one of {@code confirmation|shipped|delivered|welcome|digest}
     * @param to   the recipient email (required, non-blank)
     * @return a small JSON confirmation payload
     */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.OK)
    public TestSendResponse sendTest(@RequestParam("type") String type,
                                     @RequestParam("to") String to) {
        if (to == null || to.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient 'to' is required.");
        }
        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        RenderedEmail rendered = render(normalizedType);
        mailService.send(rendered.toMessage(to.trim()));
        return new TestSendResponse(true, normalizedType, to.trim());
    }

    /** Builds sample data + renders for the requested type. */
    private RenderedEmail render(String type) {
        return switch (type) {
            case "confirmation" -> emailRenderer.renderOrderConfirmation(
                    new EmailModels.OrderConfirmation("Aisha Khan", "SHR-1042", new BigDecimal("1299.00")));
            case "shipped" -> emailRenderer.renderOrderShipped(
                    new EmailModels.OrderShipped("Aisha Khan", "SHR-1042", "Shifa Express",
                            "AWB123456789", "https://track.example.com/AWB123456789",
                            LocalDate.now().plusDays(3), new BigDecimal("1299.00")));
            case "delivered" -> emailRenderer.renderOrderDelivered(
                    new EmailModels.OrderDelivered("Aisha Khan", "SHR-1042"));
            case "welcome" -> emailRenderer.renderWelcome(
                    new EmailModels.Welcome("Aisha Khan"));
            case "digest" -> emailRenderer.renderDigest(
                    new EmailModels.Digest(LocalDate.now(), 12, new BigDecimal("18450.00"),
                            8, new BigDecimal("12300.00"), 4, new BigDecimal("6150.00"), 2));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown type '" + type + "'. Expected one of: "
                            + "confirmation, shipped, delivered, welcome, digest.");
        };
    }

    /** JSON confirmation returned by the test-send endpoint. */
    public record TestSendResponse(boolean sent, String type, String to) {
    }
}
