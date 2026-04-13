package com.shortel.redirect.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only mirror of the url_access_list table.
 * Used to authorise visitor access to PRIVATE URLs.
 * Rows are managed by url-service; redirect-service only reads this table.
 */
@Entity
@Table(name = "url_access_list")
@IdClass(UrlAccessListId.class)
@Data
@NoArgsConstructor
public class UrlAccessList {

    @Id
    @Column(name = "url_id")
    private Long urlId;

    @Id
    @Column(name = "user_id")
    private Long userId;
}
