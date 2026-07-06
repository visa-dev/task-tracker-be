package com.tasktracker.config;

import com.tasktracker.entity.Role;
import com.tasktracker.entity.User;
import com.tasktracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Runs once on every application startup. If no ROLE_ADMIN account exists yet, creates one
 * using app.admin.username / app.admin.password (see application.properties - both
 * overridable via APP_ADMIN_USERNAME / APP_ADMIN_PASSWORD env vars).
 * <p>
 * This replaces the old workflow of registering a user and manually flipping their role to
 * ROLE_ADMIN in MySQL - it now happens automatically the first time the app boots against
 * an empty database, and is a safe no-op on every later restart once an admin exists.
 */
@Component
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.username:admin}")
    private String defaultAdminUsername;

    @Value("${app.admin.password:Admin@123}")
    private String defaultAdminPassword;

    @Override
    public void run(String... args) {
        if (userRepository.existsByRole(Role.ROLE_ADMIN)) {
            log.debug("An admin account already exists - skipping default admin creation.");
            return;
        }

        if (userRepository.existsByUsername(defaultAdminUsername)) {
            log.warn("No admin exists yet, but username '{}' is already taken by a non-admin account. "
                    + "Set app.admin.username (or APP_ADMIN_USERNAME) to a free username, or promote "
                    + "an existing user to ROLE_ADMIN manually.", defaultAdminUsername);
            return;
        }

        User admin = User.builder()
                .username(defaultAdminUsername)
                .password(passwordEncoder.encode(defaultAdminPassword))
                .role(Role.ROLE_ADMIN)
                .active(true)
                .build();
        userRepository.save(admin);

        log.warn("=================================================================");
        log.warn(" No admin account existed yet - created a default one on startup:");
        log.warn("   username: {}", defaultAdminUsername);
        log.warn("   password: {}", defaultAdminPassword);
        log.warn(" Log in and change this password (or set APP_ADMIN_USERNAME / APP_ADMIN_PASSWORD");
        log.warn(" env vars before first startup) - do not leave the default password in place.");
        log.warn("=================================================================");
    }
}
