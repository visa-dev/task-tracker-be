package com.tasktracker.service;

import com.tasktracker.dto.AuthRequest;
import com.tasktracker.dto.AuthResponse;
import com.tasktracker.dto.RegisterRequest;
import com.tasktracker.entity.Role;
import com.tasktracker.entity.User;
import com.tasktracker.exception.BadRequestException;
import com.tasktracker.repository.UserRepository;
import com.tasktracker.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            log.warn("Registration rejected - username already taken: {}", request.getUsername());
            throw new BadRequestException("Username already taken");
        }

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.ROLE_USER) // always ROLE_USER on self-registration
                .build();

        User saved = userRepository.save(user);
        log.info("New user registered: id={}, username={}", saved.getId(), saved.getUsername());

        UserDetails userDetails = userDetailsService.loadUserByUsername(saved.getUsername());
        String token = jwtUtil.generateToken(userDetails);

        return AuthResponse.builder()
                .token(token)
                .userId(saved.getId())
                .username(saved.getUsername())
                .role(saved.getRole())
                .build();
    }

    public AuthResponse login(AuthRequest request) {
        // Throws BadCredentialsException (wrong password) or DisabledException (deactivated
        // account) - both are handled centrally by GlobalExceptionHandler. Not caught here
        // so the real reason for failure is preserved instead of being flattened to one message.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadRequestException("Invalid username or password"));

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtUtil.generateToken(userDetails);

        log.info("User logged in: username={}, role={}", user.getUsername(), user.getRole());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build();
    }
}
