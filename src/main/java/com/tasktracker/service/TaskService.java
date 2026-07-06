package com.tasktracker.service;

import com.tasktracker.dto.TaskDTO;
import com.tasktracker.dto.TaskRequest;
import com.tasktracker.dto.TaskStatsResponse;
import com.tasktracker.entity.Role;
import com.tasktracker.entity.Task;
import com.tasktracker.entity.TaskPriority;
import com.tasktracker.entity.TaskStatus;
import com.tasktracker.entity.User;
import com.tasktracker.exception.BadRequestException;
import com.tasktracker.exception.ResourceNotFoundException;
import com.tasktracker.repository.TaskRepository;
import com.tasktracker.repository.UserRepository;
import com.tasktracker.websocket.TaskEventPublisher;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final TaskEventPublisher taskEventPublisher;

    @Transactional(readOnly = true)
    public Page<TaskDTO> getTasks(User currentUser, TaskStatus status, Long ownerId, String search,
                                   Boolean unassigned, Boolean overdue, Pageable pageable) {
        boolean isAdmin = currentUser.getRole() == Role.ROLE_ADMIN;
        boolean unassignedOnly = isAdmin && Boolean.TRUE.equals(unassigned);
        boolean overdueOnly = Boolean.TRUE.equals(overdue);
        Long effectiveOwnerId = isAdmin ? ownerId : currentUser.getId();

        Specification<Task> spec = buildSpecification(effectiveOwnerId, status, search, unassignedOnly, overdueOnly);
        return taskRepository.findAll(spec, pageable).map(TaskDTO::fromEntity);
    }

    @Transactional(readOnly = true)
    public TaskDTO getTaskById(User currentUser, Long taskId) {
        Task task = findTaskOrThrow(taskId);
        assertReadAccess(currentUser, task);
        return TaskDTO.fromEntity(task);
    }

    @Transactional
    public TaskDTO createTask(User currentUser, TaskRequest request) {
        validateDueDateNotInPast(request.getDueDate());

        User owner;
        User assignedBy = null;
        TaskStatus initialStatus;

        if (currentUser.getRole() == Role.ROLE_ADMIN) {
            if (request.getOwnerId() != null) {
                User target = userRepository.findById(request.getOwnerId())
                        .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.getOwnerId()));

                if (target.getId().equals(currentUser.getId())) {
                    // Admin assigning the task to themselves - same as any self-created task.
                    owner = currentUser;
                    initialStatus = TaskStatus.IN_PROGRESS;
                } else {
                    assertValidAssignmentTarget(target);
                    owner = target;
                    assignedBy = currentUser;
                    initialStatus = TaskStatus.PENDING; // assigned to someone else, awaiting pickup
                }
            } else {
                // No "Assign to" selection -> genuinely Unassigned until an Admin assigns it later.
                owner = null;
                initialStatus = TaskStatus.UNASSIGNED;
            }
        } else {
            // A user creating their own task is presumably starting it right away.
            owner = currentUser;
            initialStatus = TaskStatus.IN_PROGRESS;
        }

        TaskPriority priority = request.getPriority() != null ? request.getPriority() : TaskPriority.MEDIUM;

        Task task = Task.builder()
                .title(HtmlUtils.htmlEscape(request.getTitle()))
                .description(HtmlUtils.htmlEscape(request.getDescription()))
                .status(initialStatus) // CRITICAL: any client-supplied status is always ignored on create
                .priority(priority)
                .dueDate(request.getDueDate())
                .owner(owner)
                .assignedBy(assignedBy)
                .build();

        Task saved = taskRepository.save(task);
        log.info("Task created: id={}, owner={}, assignedBy={}, status={}, priority={}",
                saved.getId(),
                owner != null ? owner.getUsername() : "UNASSIGNED",
                assignedBy != null ? assignedBy.getUsername() : "-",
                initialStatus, priority);

        taskEventPublisher.publishCreated(saved.getId());
        return TaskDTO.fromEntity(saved);
    }

    @Transactional
    public TaskDTO updateTask(User currentUser, Long taskId, TaskRequest request) {
        Task task = findTaskOrThrow(taskId);
        assertOwnershipForWrite(currentUser, task);
        validateDueDateNotInPast(request.getDueDate());

        task.setTitle(HtmlUtils.htmlEscape(request.getTitle()));
        task.setDescription(HtmlUtils.htmlEscape(request.getDescription()));
        task.setDueDate(request.getDueDate());
        if (request.getPriority() != null) {
            task.setPriority(request.getPriority());
        }
        if (request.getStatus() != null) {
            assertCanChangeStatus(task);
            task.setStatus(request.getStatus());
        }

        Task saved = taskRepository.save(task);
        log.info("Task updated: id={}, by user={}", taskId, currentUser.getUsername());
        taskEventPublisher.publishUpdated(saved.getId());
        return TaskDTO.fromEntity(saved);
    }

    /** Quick inline status change (table row click), without needing the full edit form. */
    @Transactional
    public TaskDTO updateStatus(User currentUser, Long taskId, TaskStatus newStatus) {
        Task task = findTaskOrThrow(taskId);
        assertOwnershipForWrite(currentUser, task);
        assertCanChangeStatus(task);

        task.setStatus(newStatus);
        Task saved = taskRepository.save(task);
        log.info("Task id={} status changed to {} by user={}", taskId, newStatus, currentUser.getUsername());
        taskEventPublisher.publishUpdated(saved.getId());
        return TaskDTO.fromEntity(saved);
    }

    /** Admin-only: assign an Unassigned task, or reassign an existing one, to a specific user. */
    @Transactional
    public TaskDTO assignTask(User currentAdmin, Long taskId, Long newOwnerId) {
        Task task = findTaskOrThrow(taskId);

        User newOwner = userRepository.findById(newOwnerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + newOwnerId));

        boolean assigningToSelf = newOwner.getId().equals(currentAdmin.getId());
        if (!assigningToSelf) {
            assertValidAssignmentTarget(newOwner);
        }

        boolean wasUnassigned = task.getStatus() == TaskStatus.UNASSIGNED;

        task.setOwner(newOwner);
        task.setAssignedBy(assigningToSelf ? null : currentAdmin);

        // A task moving out of Unassigned picks up the same default status a fresh
        // task would get for that owner (IN_PROGRESS for self, PENDING for someone else).
        // Reassigning an already-assigned task leaves its current status untouched.
        if (wasUnassigned) {
            task.setStatus(assigningToSelf ? TaskStatus.IN_PROGRESS : TaskStatus.PENDING);
        }

        Task saved = taskRepository.save(task);
        log.info("Task id={} assigned to owner={} by admin={}", taskId, newOwner.getUsername(), currentAdmin.getUsername());
        taskEventPublisher.publishUpdated(saved.getId());
        return TaskDTO.fromEntity(saved);
    }

    @Transactional
    public void deleteTask(User currentUser, Long taskId) {
        Task task = findTaskOrThrow(taskId);
        assertOwnershipForWrite(currentUser, task);

        taskRepository.delete(task);
        log.info("Task deleted: id={}, by user={}", taskId, currentUser.getUsername());
        taskEventPublisher.publishDeleted(taskId);
    }

    @Transactional(readOnly = true)
    public TaskStatsResponse getStats(User currentUser) {
        LocalDate today = LocalDate.now();

        if (currentUser.getRole() == Role.ROLE_ADMIN) {
            long total = taskRepository.count();
            long pending = taskRepository.countByStatus(TaskStatus.PENDING);
            long inProgress = taskRepository.countByStatus(TaskStatus.IN_PROGRESS);
            long completed = taskRepository.countByStatus(TaskStatus.COMPLETED);
            long overdue = taskRepository.countByDueDateBeforeAndStatusNot(today, TaskStatus.COMPLETED);
            long unassigned = taskRepository.countByOwnerIsNull();
            return new TaskStatsResponse(total, pending, inProgress, completed, overdue, unassigned);
        }

        Long userId = currentUser.getId();
        long total = taskRepository.countByOwnerId(userId);
        long pending = taskRepository.countByOwnerIdAndStatus(userId, TaskStatus.PENDING);
        long inProgress = taskRepository.countByOwnerIdAndStatus(userId, TaskStatus.IN_PROGRESS);
        long completed = taskRepository.countByOwnerIdAndStatus(userId, TaskStatus.COMPLETED);
        long overdue = taskRepository.countByOwnerIdAndDueDateBeforeAndStatusNot(userId, today, TaskStatus.COMPLETED);
        return new TaskStatsResponse(total, pending, inProgress, completed, overdue, 0);
    }

    // --- helpers ---

    private Task findTaskOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
    }

    private void assertReadAccess(User currentUser, Task task) {
        if (currentUser.getRole() == Role.ROLE_ADMIN) {
            return;
        }
        if (task.getOwner() == null || !task.getOwner().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You do not have access to this task");
        }
    }

    private void assertOwnershipForWrite(User currentUser, Task task) {
        if (currentUser.getRole() == Role.ROLE_ADMIN) {
            return;
        }
        if (task.getOwner() == null || !task.getOwner().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You can only modify your own tasks");
        }
    }

    /** A task must be assigned to someone before its status can be changed by anyone. */
    private void assertCanChangeStatus(Task task) {
        if (task.getOwner() == null || task.getStatus() == TaskStatus.UNASSIGNED) {
            throw new BadRequestException("Assign this task to a user before changing its status");
        }
    }

    private void validateDueDateNotInPast(LocalDate dueDate) {
        if (dueDate != null && dueDate.isBefore(LocalDate.now())) {
            throw new BadRequestException("Due date cannot be in the past");
        }
    }

    /** Tasks may only be assigned to active, non-admin users - never to another Admin. */
    private void assertValidAssignmentTarget(User target) {
        if (!target.isActive()) {
            throw new BadRequestException("Cannot assign a task to a deactivated user");
        }
        if (target.getRole() == Role.ROLE_ADMIN) {
            throw new BadRequestException("Tasks can only be assigned to regular users, not other admins");
        }
    }

    private Specification<Task> buildSpecification(Long ownerId, TaskStatus status, String search,
                                                     boolean unassignedOnly, boolean overdueOnly) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (unassignedOnly) {
                predicates.add(cb.isNull(root.get("owner")));
            } else if (ownerId != null) {
                predicates.add(cb.equal(root.get("owner").get("id"), ownerId));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (search != null && !search.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + search.trim().toLowerCase() + "%"));
            }

            if (overdueOnly) {
                predicates.add(cb.lessThan(root.get("dueDate"), LocalDate.now()));
                predicates.add(cb.notEqual(root.get("status"), TaskStatus.COMPLETED));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
