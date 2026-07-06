package com.tasktracker.dto;

import com.tasktracker.entity.TaskPriority;
import com.tasktracker.entity.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class TaskRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must be at most 200 characters")
    private String title;

    @NotBlank(message = "Description is required")
    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    // Only honored on UPDATE. Ignored on CREATE (server forces a default based on who owns it).
    private TaskStatus status;

    // Optional on both create/update - defaults to MEDIUM in the service layer if omitted.
    private TaskPriority priority;

    @NotNull(message = "Due date is required")
    private LocalDate dueDate;

    // Admin-only: assign task to a specific user on create. Ignored for regular users.
    // Omitted entirely (null) on create by an Admin => task is created "Unassigned".
    private Long ownerId;
}
