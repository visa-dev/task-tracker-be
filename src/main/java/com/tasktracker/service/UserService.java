package com.tasktracker.service;

import com.tasktracker.dto.UserSummaryDTO;
import com.tasktracker.entity.User;
import com.tasktracker.exception.BadRequestException;
import com.tasktracker.exception.ResourceNotFoundException;
import com.tasktracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public User getCurrentUser(Authentication authentication) {
        String username = ((UserDetails) authentication.getPrincipal()).getUsername();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    /**
     * Used by the Admin "owner filter"/"assign to user" dropdowns and the Admin user
     * management page - includes role and active status so the UI can render toggles.
     */
    public List<UserSummaryDTO> getAllUsers() {
        return userRepository.findAll().stream()
                .map(u -> UserSummaryDTO.builder()
                        .id(u.getId())
                        .username(u.getUsername())
                        .role(u.getRole())
                        .active(u.isActive())
                        .build())
                .toList();
    }

    @Transactional
    public UserSummaryDTO setActive(Long userId, boolean active, User actingAdmin) {
        if (userId.equals(actingAdmin.getId()) && !active) {
            throw new BadRequestException("You cannot deactivate your own account");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        user.setActive(active);
        User saved = userRepository.save(user);

        log.info("User {} by admin={}: username={}, id={}",
                active ? "activated" : "deactivated", actingAdmin.getUsername(), saved.getUsername(), saved.getId());

        return UserSummaryDTO.builder()
                .id(saved.getId())
                .username(saved.getUsername())
                .role(saved.getRole())
                .active(saved.isActive())
                .build();
    }
}
