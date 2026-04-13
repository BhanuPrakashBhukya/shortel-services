package com.shortel.redirect.repository;

import com.shortel.redirect.entity.UrlAccessList;
import com.shortel.redirect.entity.UrlAccessListId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UrlAccessListRepository extends JpaRepository<UrlAccessList, UrlAccessListId> {
    boolean existsByUrlIdAndUserId(Long urlId, Long userId);
}
