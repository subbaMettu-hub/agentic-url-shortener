package com.assessment.urlshortener.service;

import com.assessment.urlshortener.config.AppProperties;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class ShortCodeGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private final SecureRandom random = new SecureRandom();
    private final int length;

    public ShortCodeGenerator(AppProperties appProperties) {
        this.length = appProperties.getShortCode().getMinLength();
    }

    public String generate() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
