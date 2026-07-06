package com.tasktracker.dto;

import com.tasktracker.entity.Task;
import com.tasktracker.entity.TaskPriority;
import com.tasktracker.entity.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDTO {
    private Long id;
    private String title;
    private String description;
    private TaskStatus status;
    private TaskPriority priority;
    private LocalDate dueDate;
    private Long ownerId;
    private String ownerUsername;
    private Long assignedById;
    private String assignedByUsername;
    private boolean isOverdue;
    private boolean unassigned;

    public static TaskDTO fromEntity(Task task) {
        boolean overdue = task.getDueDate() != null
                && task.getDueDate().isBefore(LocalDate.now())
                && task.getStatus() != TaskStatus.COMPLETED;

        TaskDTO.TaskDTOBuilder builder = TaskDTO.builder()
                .id(task.getId())
                .title(task.getTitle())
                .description(task.getDescription())
                .status(task.getStatus())
                .priority(task.getPriority())
                .dueDate(task.getDueDate())
                .isOverdue(overdue)
                .unassigned(task.getOwner() == null);

        if (task.getOwner() != null) {
            builder.ownerId(task.getOwner().getId())
                    .ownerUsername(task.getOwner().getUsername());
        }

        if (task.getAssignedBy() != null) {
            builder.assignedById(task.getAssignedBy().getId())
                    .assignedByUsername(task.getAssignedBy().getUsername());
        }

        return builder.build();
    }
}
