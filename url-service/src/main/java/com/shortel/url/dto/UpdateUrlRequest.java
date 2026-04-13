package com.shortel.url.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateUrlRequest {

    @Size(max = 2048, message = "longUrl must not exceed 2048 characters")
    @Pattern(regexp = "^https?://.*", message = "longUrl must be a valid http or https URL")
    private String longUrl;

    @Pattern(regexp = "^(?i)(PUBLIC|PRIVATE)$", message = "visibility must be PUBLIC or PRIVATE")
    private String visibility;

    private LocalDateTime expiresAt;
}