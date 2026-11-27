package com.blog.mail;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SMTP transport for transactional mail. Sending runs on a single daemon
 * thread so a slow or unreachable SMTP server cannot delay HTTP responses.
 */
@Slf4j
@Component
public class SmtpMailGateway implements MailGateway {

    private final JavaMailSender sender;
    private final boolean mailEnabled;
    private final boolean hostConfigured;
    private final String from;
    private final String siteBaseUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mail-sender");
        thread.setDaemon(true);
        return thread;
    });

    public SmtpMailGateway(
            ObjectProvider<JavaMailSender> senderProvider,
            @Value("${blog.mail.enabled:false}") boolean mailEnabled,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.username:}") String username,
            @Value("${blog.mail.from:}") String from,
            @Value("${blog.mail.site-base-url:https://wynneyin.site}") String siteBaseUrl) {
        this.sender = senderProvider.getIfAvailable();
        this.mailEnabled = mailEnabled;
        this.hostConfigured = host != null && !host.isBlank();
        this.from = from == null ? "" : from.trim();
        String base = siteBaseUrl == null ? "" : siteBaseUrl.trim();
        this.siteBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        // JavaMail fails every send when smtp.auth=true has no credentials.
        // An internal relay without AUTH is configured by leaving MAIL_USERNAME
        // empty, which is what the docs promise, so mirror that here.
        if (this.sender instanceof JavaMailSenderImpl implementation
                && (username == null || username.isBlank())) {
            Properties properties = implementation.getJavaMailProperties();
            if (Boolean.parseBoolean(properties.getProperty("mail.smtp.auth", "false"))) {
                properties.setProperty("mail.smtp.auth", "false");
                log.info("MAIL_USERNAME is empty; SMTP AUTH is disabled");
            }
        }
        if (this.mailEnabled && !enabled()) {
            log.warn("Mail is enabled but MAIL_HOST/MAIL_FROM is missing; sending stays off");
        }
    }

    @Override
    public boolean enabled() {
        return mailEnabled && sender != null && hostConfigured && !from.isEmpty();
    }

    @Override
    public String siteBaseUrl() {
        return siteBaseUrl;
    }

    @Override
    public boolean send(String to, String subject, String text) {
        if (!enabled() || to == null || !to.contains("@")) {
            return false;
        }
        JavaMailSender mailSender = sender;
        String recipient = to.trim();
        executor.submit(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
                helper.setFrom(from);
                helper.setTo(recipient);
                helper.setSubject(subject);
                helper.setText(text, false);
                mailSender.send(message);
            } catch (Exception e) {
                // Recipient addresses are personal data; keep them out of logs.
                log.warn("Failed to send mail '{}': {}", subject, e.getMessage());
            }
        });
        return true;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
