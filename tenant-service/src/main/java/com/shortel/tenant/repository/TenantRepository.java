package com.shortel.tenant.repository;

import com.shortel.tenant.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findByEmail(String email);
    List<Tenant> findAllByActiveTrue();
}
