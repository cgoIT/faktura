package de.cgoit.faktura.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Mail sender for tests: keeps the messages instead of talking to a server, can fail on demand and lets a test
 * look at the state of the application while the "SMTP server" is busy.
 */
public class CapturingMailSender extends JavaMailSenderImpl {

    private final List<MimeMessage> sent = new ArrayList<>();
    private RuntimeException failure;
    private Consumer<MimeMessage> beforeSend = message -> { };

    public List<MimeMessage> sent() {
        return sent;
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void beforeSend(Consumer<MimeMessage> action) {
        this.beforeSend = action;
    }

    public void reset() {
        sent.clear();
        failure = null;
        beforeSend = message -> { };
    }

    @Override
    public void send(MimeMessage... mimeMessages) throws MailException {
        for (var message : mimeMessages) {
            beforeSend.accept(message);

            if (failure != null) {
                throw failure;
            }

            try {
                message.saveChanges();
            } catch (MessagingException e) {
                throw new MailSendException("Could not prepare the message", e);
            }

            sent.add(message);
        }
    }
}
