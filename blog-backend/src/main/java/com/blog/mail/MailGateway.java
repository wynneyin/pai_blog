package com.blog.mail;

/**
 * Outbound mail capability. Implementations must never block the request
 * thread, and a failed send must not fail the business operation that
 * triggered it (order creation, password reset request, ...).
 */
public interface MailGateway {

    /** True only when SMTP is configured and sending is switched on. */
    boolean enabled();

    /** Public site root, without a trailing slash. Used to build reset links. */
    String siteBaseUrl();

    /**
     * Queues a plain-text message. Returns false when mail is disabled, the
     * recipient is not an email address, or the message could not be queued.
     */
    boolean send(String to, String subject, String text);
}
