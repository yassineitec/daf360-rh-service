package com.daf360.rh.service.pdf;

import com.daf360.rh.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfClientService {

    private final AppProperties appProperties;

    /**
     * Uses SimpleClientHttpRequestFactory (classic HttpURLConnection) instead of the
     * JDK HttpClient default, which is incompatible with the Express-based pdf-service.
     * Generous read timeout — PDF rendering (Playwright/Chromium) can take a few seconds.
     */
    private final RestClient restClient = RestClient.builder()
            .requestFactory(pdfRequestFactory())
            .build();

    private static SimpleClientHttpRequestFactory pdfRequestFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);
        f.setReadTimeout(60_000);
        return f;
    }

    public byte[] generatePdf(String templateSlug, Map<String, Object> data) {
        String url = appProperties.getPdfServiceUrl() + "/pdf/api/arx/" + templateSlug;
        return post(url, data, templateSlug);
    }

    /**
     * Renders arbitrary HTML to PDF via the generic /pdf/api/render endpoint.
     * The caller must have already resolved all {{variable}} tokens in the HTML.
     */
    public byte[] generatePdfFromHtml(String html, String filename) {
        String url = appProperties.getPdfServiceUrl() + "/pdf/api/render";
        Map<String, String> body = Map.of("html", html, "filename", filename);
        return post(url, body, "render:" + filename);
    }

    private byte[] post(String url, Object body, String label) {
        try {
            byte[] bytes = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new PdfGenerationException("Empty PDF response from pdf-service");
            }
            log.info("PDF generated: label={} size={}B", label, bytes.length);
            return bytes;
        } catch (RestClientException e) {
            log.error("PDF service failed label={}: {}", label, e.getMessage());
            throw new PdfGenerationException("PDF service unavailable: " + e.getMessage(), e);
        }
    }
}
