package com.tasktracker.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(
        name = "tasks",
        indexes = {
                @Index(name = "idx_task_owner_id", columnList = "owner_id"),
                @Index(name = "idx_task_status", columnList = "status"),
                @Index(name = "idx_task_priority", columnList = "priority"),
                @Index(name = "idx_task_due_date", columnList = "due_date")
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    // Nullable: a task created by an Admin without picking anyone in "Assign to" has no
    // owner yet and lives in the Admin-only "Unassigned" bucket until it's assigned.
    // No cascade / no orphan removal configured => FK behaves as ON DELETE RESTRICT
    // (deleting a User who owns tasks will fail with a DB constraint violation).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", foreignKey = @ForeignKey(name = "fk_task_owner"))
    private User owner;

    // Set ONLY when an Admin creates/assigns this task to a different user.
    // Null when a user creates their own task, when an Admin creates a task for themselves,
    // or while the task is still unassigned. Lets the owner's UI show "Assigned by: <admin>".
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_id", foreignKey = @ForeignKey(name = "fk_task_assigned_by"))
    private User assignedBy;
}
