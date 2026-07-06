package com.tasktracker.dto;

import com.tasktracker.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskStatusUpdateRequest {

    @NotNull(message = "status is required")
    private TaskStatus status;
}
