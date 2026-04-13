package com.shortel.redirect.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyPasswordRequest {

    @NotBlank(message = "password is required")
    private String password;
}
