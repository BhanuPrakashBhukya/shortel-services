package com.shortel.redirect.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UrlAccessListId implements Serializable {
    private Long urlId;
    private Long userId;
}
