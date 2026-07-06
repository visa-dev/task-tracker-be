package com.tasktracker.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        // JwtUtil reads these via @Value in a running Spring context; set them directly here
        // since this is a plain unit test with no ApplicationContext.
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", "unit-test-secret-key-must-be-long-enough-for-hs256");
        ReflectionTestUtils.setField(jwtUtil, "jwtExpirationMs", 3600000L);

        userDetails = org.springframework.security.core.userdetails.User
                .withUsername("alice").password("x").authorities("ROLE_USER").build();
    }

    @Test
    void generateToken_thenExtractUsername_roundTrips() {
        String token = jwtUtil.generateToken(userDetails);

        assertNotNull(token);
        assertEquals("alice", jwtUtil.extractUsername(token));
    }

    @Test
    void isTokenValid_forCorrectUserAndUnexpiredToken_returnsTrue() {
        String token = jwtUtil.generateToken(userDetails);

        assertTrue(jwtUtil.isTokenValid(token, userDetails));
    }

    @Test
    void isTokenValid_forDifferentUser_returnsFalse() {
        String token = jwtUtil.generateToken(userDetails);

        UserDetails otherUser = org.springframework.security.core.userdetails.User
                .withUsername("bob").password("x").authorities("ROLE_USER").build();

        assertFalse(jwtUtil.isTokenValid(token, otherUser));
    }

    @Test
    void isTokenValid_forExpiredToken_throwsExpiredJwtException() {
        // JJWT validates the exp claim while parsing, so an already-expired token throws
        // during extractUsername()/isTokenValid() rather than quietly returning false - this
        // is exactly why JwtAuthFilter wraps its token parsing in a try/catch for JwtException.
        ReflectionTestUtils.setField(jwtUtil, "jwtExpirationMs", -1000L);
        String expiredToken = jwtUtil.generateToken(userDetails);

        assertThrows(io.jsonwebtoken.ExpiredJwtException.class,
                () -> jwtUtil.isTokenValid(expiredToken, userDetails));
    }
}
