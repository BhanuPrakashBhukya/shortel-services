package com.shortel.auth.service;

import com.shortel.auth.entity.User;
import com.shortel.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Authenticate user by email + password.
     * Returns the User if credentials are valid.
     */
    public User authenticate(String email, String rawPassword) {
        User user = userRepository.findByEmailAndActiveTrue(email)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        return user;
    }

    /**
     * Register a new user. Email must be unique.
     */
    public User register(String email, String rawPassword, String name, Long tenantId) {
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Email already registered: " + email);
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setName(name);
        user.setTenantId(tenantId);

        User saved = userRepository.save(user);
        log.info("Registered new user: email={} tenantId={}", email, tenantId);
        return saved;
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }
}
