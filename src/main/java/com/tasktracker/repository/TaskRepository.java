package com.tasktracker.repository;

import com.tasktracker.entity.Task;
import com.tasktracker.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;

public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {
    long countByOwnerIdAndStatus(Long ownerId, TaskStatus status);
    long countByOwnerId(Long ownerId);
    long countByStatus(TaskStatus status);

    // Admin-only "Unassigned" bucket: tasks created without an owner.
    long countByOwnerIsNull();

    // Overdue = due date in the past AND not yet completed.
    long countByDueDateBeforeAndStatusNot(LocalDate date, TaskStatus excludedStatus);
    long countByOwnerIdAndDueDateBeforeAndStatusNot(Long ownerId, LocalDate date, TaskStatus excludedStatus);
}
