package com.engine.brailleai.vision.client;

import com.engine.brailleai.vision.dto.DotDetectionResponseDto;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * HTTP client for the external Python vision service.
 */
public class VisionClient {

    private final WebClient webClient;
    private final String visionServiceBaseUrl;

    public VisionClient(String visionServiceBaseUrl) {
        this.visionServiceBaseUrl = visionServiceBaseUrl;
        this.webClient = WebClient.builder()
                .baseUrl(visionServiceBaseUrl)
                .build();
    }

    /**
     * Sends the image to the vision service and returns detected dot metadata.
     */
    public DotDetectionResponseDto detectDots(MultipartFile image) {
        try {
            return detectDots(
                    image == null ? null : image.getOriginalFilename(),
                    image == null ? null : image.getContentType(),
                    image == null ? null : image.getBytes()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to call vision service at " + visionServiceBaseUrl, e);
        }
    }

    /**
     * Sends arbitrary image bytes to the vision service and returns detected dot metadata.
     */
    public DotDetectionResponseDto detectDots(
            String filename,
            String contentType,
            byte[] imageBytes
    ) {
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            byte[] payload = imageBytes == null ? new byte[0] : imageBytes;
            String safeFilename = (filename == null || filename.isBlank()) ? "upload-image" : filename;
            MediaType partType = MediaType.APPLICATION_OCTET_STREAM;
            if (contentType != null && !contentType.isBlank()) {
                try {
                    partType = MediaType.parseMediaType(contentType);
                } catch (IllegalArgumentException ignored) {
                    partType = MediaType.APPLICATION_OCTET_STREAM;
                }
            }

            builder.part("image", new ByteArrayResource(payload) {
                @Override
                public String getFilename() {
                    return safeFilename;
                }
            }).contentType(partType);

            return webClient.post()
                    .uri("/vision/detect-dots")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(DotDetectionResponseDto.class)
                    .block();

        } catch (Exception e) {
            throw new RuntimeException("Failed to call vision service at " + visionServiceBaseUrl, e);
        }
    }
}
