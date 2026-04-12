package com.shortel.auth.dto;

import lombok.Data;

@Data
public class RefreshRequest {
    private String refreshToken;
}
