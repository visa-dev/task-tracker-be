package com.tasktracker.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserStatusRequest {

    @NotNull(message = "active is required")
    private Boolean active;
}
