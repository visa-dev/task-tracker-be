package com.tasktracker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatsResponse {
    private long total;
    private long pending;
    private long inProgress;
    private long completed;
    private long overdue;

    // Admin-only concept: tasks created without an owner, awaiting assignment.
    // Always 0 for a ROLE_USER caller (they can never own an unassigned task).
    private long unassigned;
}
