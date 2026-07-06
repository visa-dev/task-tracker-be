package com.tasktracker.controller;

import com.tasktracker.dto.ApiResponse;
import com.tasktracker.dto.UserStatusRequest;
import com.tasktracker.dto.UserSummaryDTO;
import com.tasktracker.entity.User;
import com.tasktracker.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // Admin-only: populates the "Filter by Owner" / "Assign to User" dropdowns and the
    // Admin user-management sidebar.
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<UserSummaryDTO>>> getAllUsers() {
        List<UserSummaryDTO> users = userService.getAllUsers();
        return ResponseEntity.ok(ApiResponse.success("Users retrieved", users));
    }

    // Admin-only: activate/deactivate a user account. A deactivated user can neither log in
    // nor use an existing token (enforced in CustomUserDetailsService + JwtAuthFilter).
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserSummaryDTO>> setUserStatus(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UserStatusRequest request) {

        User actingAdmin = userService.getCurrentUser(authentication);
        UserSummaryDTO updated = userService.setActive(id, request.getActive(), actingAdmin);
        String message = request.getActive() ? "User activated" : "User deactivated";
        return ResponseEntity.ok(ApiResponse.success(message, updated));
    }
}
