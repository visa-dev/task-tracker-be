package com.tasktracker.service;

import com.tasktracker.dto.UserSummaryDTO;
import com.tasktracker.entity.Role;
import com.tasktracker.entity.User;
import com.tasktracker.exception.BadRequestException;
import com.tasktracker.exception.ResourceNotFoundException;
import com.tasktracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private User admin;
    private User targetUser;

    @BeforeEach
    void setUp() {
        admin = User.builder().id(1L).username("admin").password("x").role(Role.ROLE_ADMIN).active(true).build();
        targetUser = User.builder().id(2L).username("bob").password("x").role(Role.ROLE_USER).active(true).build();
    }

    @Test
    void getAllUsers_mapsEntitiesToSummaryDTOsWithActiveStatus() {
        when(userRepository.findAll()).thenReturn(List.of(admin, targetUser));

        List<UserSummaryDTO> result = userService.getAllUsers();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(u -> u.getUsername().equals("admin") && u.getRole() == Role.ROLE_ADMIN));
        assertTrue(result.stream().anyMatch(u -> u.getUsername().equals("bob") && u.isActive()));
    }

    @Test
    void setActive_deactivateAnotherUser_succeeds() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSummaryDTO result = userService.setActive(2L, false, admin);

        assertFalse(result.isActive());
        verify(userRepository).save(argThat(u -> !u.isActive()));
    }

    @Test
    void setActive_adminTriesToDeactivateSelf_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> userService.setActive(1L, false, admin));
        verify(userRepository, never()).save(any());
    }

    @Test
    void setActive_reactivateUser_succeeds() {
        targetUser.setActive(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSummaryDTO result = userService.setActive(2L, true, admin);

        assertTrue(result.isActive());
    }

    @Test
    void setActive_userNotFound_throwsResourceNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.setActive(999L, false, admin));
    }
}
