package com.assessment.urlshortener.controller;

import com.assessment.urlshortener.dto.UrlMetadataResponse;
import com.assessment.urlshortener.service.QrCodeService;
import com.assessment.urlshortener.service.UrlShortenerService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QrCodeController {

    private final UrlShortenerService urlShortenerService;
    private final QrCodeService qrCodeService;

    public QrCodeController(UrlShortenerService urlShortenerService, QrCodeService qrCodeService) {
        this.urlShortenerService = urlShortenerService;
        this.qrCodeService = qrCodeService;
    }

    @GetMapping(value = "/api/v1/urls/{shortCode}/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] getQrCode(@PathVariable String shortCode, @RequestParam(defaultValue = "300") int size) {
        UrlMetadataResponse metadata = urlShortenerService.getMetadata(shortCode);
        return qrCodeService.generatePng(metadata.shortUrl(), size);
    }
}
