package com.shifa.oms.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MockMailService} (Feature E3): it records successful
 * sends and, when failure is simulated, throws {@link MailException}.
 */
class MockMailServiceTest {

    @Test
    void recordsSuccessfulSends() {
        MockMailService service = new MockMailService();
        MailMessage message = new MailMessage("ops@shifa.local", "Hello", "Body text");

        service.send(message);

        assertThat(service.sentMessages()).containsExactly(message);
    }

    @Test
    void clearRemovesRecordedSends() {
        MockMailService service = new MockMailService();
        service.send(new MailMessage("a@shifa.local", "s1", "b1"));
        service.send(new MailMessage("b@shifa.local", "s2", "b2"));

        service.clear();

        assertThat(service.sentMessages()).isEmpty();
    }

    @Test
    void simulateFailureThrowsMailException() {
        MockMailService service = new MockMailService();
        service.simulateFailure(true);

        assertThatThrownBy(() -> service.send(new MailMessage("x@shifa.local", "s", "b")))
                .isInstanceOf(MailException.class);
        assertThat(service.sentMessages()).isEmpty();
    }

    @Test
    void simulateFailureCanBeToggledOff() {
        MockMailService service = new MockMailService();
        service.simulateFailure(true);
        assertThatThrownBy(() -> service.send(new MailMessage("x@shifa.local", "s", "b")))
                .isInstanceOf(MailException.class);

        service.simulateFailure(false);
        MailMessage ok = new MailMessage("y@shifa.local", "s2", "b2");
        service.send(ok);

        assertThat(service.sentMessages()).containsExactly(ok);
    }
}
