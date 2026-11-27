package com.blog.mail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpMailGatewayTest {

    private static ObjectProvider<JavaMailSender> senderProvider(JavaMailSender sender) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (sender != null) {
            factory.registerSingleton("mailSender", sender);
        }
        return factory.getBeanProvider(JavaMailSender.class);
    }

    @Test
    void requiresSwitchHostAndSender() {
        JavaMailSender sender = new JavaMailSenderImpl();

        assertFalse(new SmtpMailGateway(senderProvider(sender), false,
                "smtp.example.com", "", "no-reply@example.com", "https://example.test").enabled());
        assertFalse(new SmtpMailGateway(senderProvider(sender), true,
                "", "", "no-reply@example.com", "https://example.test").enabled());
        assertFalse(new SmtpMailGateway(senderProvider(sender), true,
                "   ", "", "no-reply@example.com", "https://example.test").enabled());
        assertFalse(new SmtpMailGateway(senderProvider(null), true,
                "smtp.example.com", "", "no-reply@example.com", "https://example.test").enabled());
        assertFalse(new SmtpMailGateway(senderProvider(sender), true,
                "smtp.example.com", "", "  ", "https://example.test").enabled());

        assertTrue(new SmtpMailGateway(senderProvider(sender), true,
                "smtp.example.com", "", "no-reply@example.com", "https://example.test").enabled());
    }

    @Test
    void disablesSmtpAuthWhenNoUsernameIsConfigured() {
        JavaMailSenderImpl anonymousRelay = new JavaMailSenderImpl();
        anonymousRelay.getJavaMailProperties().setProperty("mail.smtp.auth", "true");

        new SmtpMailGateway(senderProvider(anonymousRelay), true,
                "smtp.example.com", "", "no-reply@example.com", "https://example.test");
        assertEquals("false", anonymousRelay.getJavaMailProperties().getProperty("mail.smtp.auth"));

        JavaMailSenderImpl authenticatedRelay = new JavaMailSenderImpl();
        authenticatedRelay.getJavaMailProperties().setProperty("mail.smtp.auth", "true");

        new SmtpMailGateway(senderProvider(authenticatedRelay), true,
                "smtp.example.com", "mailer", "no-reply@example.com", "https://example.test");
        assertEquals("true", authenticatedRelay.getJavaMailProperties().getProperty("mail.smtp.auth"));
    }

    @Test
    void trimsTrailingSlashFromSiteBaseUrl() {
        SmtpMailGateway gateway = new SmtpMailGateway(
                senderProvider(new JavaMailSenderImpl()), true,
                "smtp.example.com", "", "no-reply@example.com", "https://example.test/");
        assertTrue(gateway.enabled());
        assertEquals("https://example.test", gateway.siteBaseUrl());
    }
}
