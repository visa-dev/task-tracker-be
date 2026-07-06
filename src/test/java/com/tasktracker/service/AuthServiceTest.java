package com.tasktracker.service;

import com.tasktracker.dto.AuthRequest;
import com.tasktracker.dto.AuthResponse;
import com.tasktracker.dto.RegisterRequest;
import com.tasktracker.entity.Role;
import com.tasktracker.entity.User;
import com.tasktracker.exception.BadRequestException;
import com.tasktracker.repository.UserRepository;
import com.tasktracker.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private UserDetailsService userDetailsService;

    @InjectMocks
    private AuthService authService;

    private User existingUser;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        existingUser = User.builder()
                .id(1L).username("alice").password("hashed").role(Role.ROLE_USER).active(true).build();

        userDetails = org.springframework.security.core.userdetails.User
                .withUsername("alice").password("hashed").authorities("ROLE_USER").build();
    }

    @Test
    void register_newUsername_createsUserWithRoleUserAndReturnsToken() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("password123");

        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });
        when(userDetailsService.loadUserByUsername("newuser")).thenReturn(userDetails);
        when(jwtUtil.generateToken(userDetails)).thenReturn("fake-jwt-token");

        AuthResponse response = authService.register(request);

        assertEquals("fake-jwt-token", response.getToken());
        assertEquals(42L, response.getUserId());
        assertEquals(Role.ROLE_USER, response.getRole());

        // Verify the saved entity was forced to ROLE_USER regardless of anything else.
        verify(userRepository).save(argThat(u -> u.getRole() == Role.ROLE_USER));
    }

    @Test
    void register_duplicateUsername_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("password123");

        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_validCredentials_returnsTokenAndUserDetails() {
        AuthRequest request = new AuthRequest();
        request.setUsername("alice");
        request.setPassword("correct-password");

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(existingUser));
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(userDetails);
        when(jwtUtil.generateToken(userDetails)).thenReturn("fake-jwt-token");

        AuthResponse response = authService.login(request);

        assertEquals("fake-jwt-token", response.getToken());
        assertEquals("alice", response.getUsername());
        assertEquals(Role.ROLE_USER, response.getRole());
        verify(authenticationManager).authenticate(any());
    }

    @Test
    void login_wrongPassword_bubblesBadCredentialsException() {
        AuthRequest request = new AuthRequest();
        request.setUsername("alice");
        request.setPassword("wrong-password");

        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any());

        assertThrows(BadCredentialsException.class, () -> authService.login(request));
    }

    @Test
    void login_deactivatedAccount_bubblesDisabledException() {
        AuthRequest request = new AuthRequest();
        request.setUsername("alice");
        request.setPassword("correct-password");

        doThrow(new DisabledException("Account disabled"))
                .when(authenticationManager).authenticate(any());

        assertThrows(DisabledException.class, () -> authService.login(request));
    }
}
