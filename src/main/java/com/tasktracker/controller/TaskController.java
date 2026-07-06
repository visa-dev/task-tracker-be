package com.tasktracker.controller;

import com.tasktracker.dto.ApiResponse;
import com.tasktracker.dto.TaskAssignRequest;
import com.tasktracker.dto.TaskDTO;
import com.tasktracker.dto.TaskRequest;
import com.tasktracker.dto.TaskStatsResponse;
import com.tasktracker.dto.TaskStatusUpdateRequest;
import com.tasktracker.entity.TaskPriority;
import com.tasktracker.entity.TaskStatus;
import com.tasktracker.entity.User;
import com.tasktracker.service.TaskService;
import com.tasktracker.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<TaskDTO>>> getTasks(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(required = false) Boolean overdue) {

        User currentUser = userService.getCurrentUser(authentication);
        Pageable pageable = PageRequest.of(page, size);
        Page<TaskDTO> tasks = taskService.getTasks(currentUser, status, priority, ownerId, search, unassigned, overdue, pageable);
        return ResponseEntity.ok(ApiResponse.success("Tasks retrieved", tasks));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<TaskStatsResponse>> getStats(Authentication authentication) {
        User currentUser = userService.getCurrentUser(authentication);
        TaskStatsResponse stats = taskService.getStats(currentUser);
        return ResponseEntity.ok(ApiResponse.success("Stats retrieved", stats));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TaskDTO>> getTaskById(Authentication authentication, @PathVariable Long id) {
        User currentUser = userService.getCurrentUser(authentication);
        TaskDTO task = taskService.getTaskById(currentUser, id);
        return ResponseEntity.ok(ApiResponse.success("Task retrieved", task));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaskDTO>> createTask(
            Authentication authentication, @Valid @RequestBody TaskRequest request) {
        User currentUser = userService.getCurrentUser(authentication);
        TaskDTO task = taskService.createTask(currentUser, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Task created", task));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TaskDTO>> updateTask(
            Authentication authentication, @PathVariable Long id, @Valid @RequestBody TaskRequest request) {
        User currentUser = userService.getCurrentUser(authentication);
        TaskDTO task = taskService.updateTask(currentUser, id, request);
        return ResponseEntity.ok(ApiResponse.success("Task updated", task));
    }

    // Quick inline status change - lets a table row update status without opening the full edit form.
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<TaskDTO>> updateStatus(
            Authentication authentication, @PathVariable Long id, @Valid @RequestBody TaskStatusUpdateRequest request) {
        User currentUser = userService.getCurrentUser(authentication);
        TaskDTO task = taskService.updateStatus(currentUser, id, request.getStatus());
        return ResponseEntity.ok(ApiResponse.success("Task status updated", task));
    }

    // Admin-only: assign an Unassigned task (or reassign any task) to a specific user.
    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TaskDTO>> assignTask(
            Authentication authentication, @PathVariable Long id, @Valid @RequestBody TaskAssignRequest request) {
        User currentAdmin = userService.getCurrentUser(authentication);
        TaskDTO task = taskService.assignTask(currentAdmin, id, request.getOwnerId());
        return ResponseEntity.ok(ApiResponse.success("Task assigned", task));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTask(Authentication authentication, @PathVariable Long id) {
        User currentUser = userService.getCurrentUser(authentication);
        taskService.deleteTask(currentUser, id);
        return ResponseEntity.ok(ApiResponse.success("Task deleted", null));
    }
}