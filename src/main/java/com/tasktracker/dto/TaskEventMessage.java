package com.tasktracker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sanitized WebSocket payload. Deliberately does NOT contain the full Task entity
 * (title/description/owner etc.) so that RBAC is not bypassed via the socket channel.
 * Clients receive this and must call GET /api/v1/tasks to refetch (RBAC enforced there).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskEventMessage {
    private String action; // CREATE | UPDATE | DELETE
    private Long taskId;
}
