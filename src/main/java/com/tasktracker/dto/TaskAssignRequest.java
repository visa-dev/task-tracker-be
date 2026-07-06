package com.tasktracker.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskAssignRequest {

    @NotNull(message = "ownerId is required")
    private Long ownerId;
}
