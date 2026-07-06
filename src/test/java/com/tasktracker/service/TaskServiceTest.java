package com.tasktracker.service;

import com.tasktracker.dto.TaskDTO;
import com.tasktracker.dto.TaskRequest;
import com.tasktracker.entity.Role;
import com.tasktracker.entity.Task;
import com.tasktracker.entity.TaskPriority;
import com.tasktracker.entity.TaskStatus;
import com.tasktracker.entity.User;
import com.tasktracker.exception.BadRequestException;
import com.tasktracker.repository.TaskRepository;
import com.tasktracker.repository.UserRepository;
import com.tasktracker.websocket.TaskEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskEventPublisher taskEventPublisher;

    @InjectMocks
    private TaskService taskService;

    private User regularUser;
    private User otherUser;
    private User adminUser;
    private User otherAdmin;
    private Task userTask;

    @BeforeEach
    void setUp() {
        regularUser = User.builder().id(1L).username("alice").password("x").role(Role.ROLE_USER).active(true).build();
        otherUser = User.builder().id(2L).username("bob").password("x").role(Role.ROLE_USER).active(true).build();
        adminUser = User.builder().id(3L).username("admin").password("x").role(Role.ROLE_ADMIN).active(true).build();
        otherAdmin = User.builder().id(4L).username("admin2").password("x").role(Role.ROLE_ADMIN).active(true).build();

        userTask = Task.builder()
                .id(100L)
                .title("Write report")
                .description("desc")
                .status(TaskStatus.PENDING)
                .priority(TaskPriority.MEDIUM)
                .dueDate(LocalDate.now().plusDays(1))
                .owner(regularUser)
                .build();
    }

    private TaskRequest baseRequest(String title) {
        TaskRequest request = new TaskRequest();
        request.setTitle(title);
        request.setDescription("Some description");
        request.setDueDate(LocalDate.now().plusDays(3));
        return request;
    }

    // --- getTasks / RBAC scoping ---

    @Test
    void getTasks_asRegularUser_onlyReturnsOwnTasks() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Task> page = new PageImpl<>(List.of(userTask));

        when(taskRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        Page<TaskDTO> result = taskService.getTasks(regularUser, null, null, /* ownerId ignored */ 999L, null, null, null, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(regularUser.getId(), result.getContent().get(0).getOwnerId());
        // The ownerId=999L passed in must have been ignored/overridden for a ROLE_USER caller.
    }

    @Test
    void getTasks_asAdmin_returnsAllTasksOrFilteredByOwner() {
        Task adminOwnedTask = Task.builder()
                .id(101L).title("Admin task").description("d").status(TaskStatus.PENDING)
                .priority(TaskPriority.MEDIUM).dueDate(LocalDate.now()).owner(otherUser).build();

        Pageable pageable = PageRequest.of(0, 10);
        Page<Task> page = new PageImpl<>(List.of(userTask, adminOwnedTask));

        when(taskRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        Page<TaskDTO> result = taskService.getTasks(adminUser, null, null, null, null, null, null, pageable);
        assertEquals(2, result.getTotalElements());
    }

    @Test
    void getTasks_asRegularUser_unassignedFlagIsIgnored() {
        // A ROLE_USER can never see Unassigned tasks, even if they somehow pass unassigned=true.
        Pageable pageable = PageRequest.of(0, 10);
        when(taskRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(userTask)));

        Page<TaskDTO> result = taskService.getTasks(regularUser, null, null, null, null, true, null, pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(regularUser.getId(), result.getContent().get(0).getOwnerId());
    }

    // --- update / ownership ---

    @Test
    void updateTask_userTriesToUpdateAnotherUsersTask_throwsAccessDenied() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));

        TaskRequest request = baseRequest("Hacked title");

        assertThrows(AccessDeniedException.class, () -> taskService.updateTask(otherUser, 100L, request));
    }

    @Test
    void updateTask_ownerUpdatesOwnTask_succeeds() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskRequest request = baseRequest("Updated title");
        request.setStatus(TaskStatus.IN_PROGRESS);
        request.setPriority(TaskPriority.HIGH);

        TaskDTO result = taskService.updateTask(regularUser, 100L, request);

        assertEquals("Updated title", result.getTitle());
        assertEquals(TaskStatus.IN_PROGRESS, result.getStatus());
        assertEquals(TaskPriority.HIGH, result.getPriority());
    }

    @Test
    void updateTask_adminCanUpdateAnyUsersTask() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskRequest request = baseRequest("Admin edited this");

        TaskDTO result = taskService.updateTask(adminUser, 100L, request);

        assertEquals("Admin edited this", result.getTitle());
    }

    @Test
    void updateTask_dueDateInPast_throwsBadRequest() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));

        TaskRequest request = baseRequest("Bad due date");
        request.setDueDate(LocalDate.now().minusDays(1));

        assertThrows(BadRequestException.class, () -> taskService.updateTask(regularUser, 100L, request));
    }

    @Test
    void updateTask_statusChangeOnUnassignedTask_throwsBadRequest() {
        Task unassignedTask = Task.builder()
                .id(500L).title("Orphan").description("d")
                .status(TaskStatus.UNASSIGNED).priority(TaskPriority.MEDIUM)
                .dueDate(LocalDate.now().plusDays(1)).owner(null).build();
        when(taskRepository.findById(500L)).thenReturn(Optional.of(unassignedTask));

        TaskRequest request = baseRequest("Trying to change status");
        request.setStatus(TaskStatus.IN_PROGRESS);

        assertThrows(BadRequestException.class, () -> taskService.updateTask(adminUser, 500L, request));
    }

    @Test
    void updateTask_nonStatusFieldsOnUnassignedTask_stillAllowed() {
        Task unassignedTask = Task.builder()
                .id(501L).title("Orphan").description("d")
                .status(TaskStatus.UNASSIGNED).priority(TaskPriority.MEDIUM)
                .dueDate(LocalDate.now().plusDays(1)).owner(null).build();
        when(taskRepository.findById(501L)).thenReturn(Optional.of(unassignedTask));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskRequest request = baseRequest("Updated title, no status change");
        // status left null -> not attempting to change it, should succeed even while Unassigned.

        TaskDTO result = taskService.updateTask(adminUser, 501L, request);

        assertEquals("Updated title, no status change", result.getTitle());
        assertEquals(TaskStatus.UNASSIGNED, result.getStatus());
    }

    // --- create: status defaults per new business rules ---

    @Test
    void createTask_regularUserCreatesOwnTask_defaultsToInProgressAndIgnoresClientStatus() {
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(200L);
            return t;
        });

        TaskRequest request = baseRequest("New task");
        request.setStatus(TaskStatus.COMPLETED); // attempt to bypass -> must be ignored

        TaskDTO result = taskService.createTask(regularUser, request);

        assertEquals(TaskStatus.IN_PROGRESS, result.getStatus());
        assertEquals(regularUser.getId(), result.getOwnerId());
        assertNull(result.getAssignedByUsername());
        assertFalse(result.isUnassigned());
    }

    @Test
    void createTask_dueDateInPast_throwsBadRequest() {
        TaskRequest request = baseRequest("Should fail");
        request.setDueDate(LocalDate.now().minusDays(1));

        assertThrows(BadRequestException.class, () -> taskService.createTask(regularUser, request));
    }

    @Test
    void createTask_dueDateToday_isAllowed() {
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(201L);
            return t;
        });

        TaskRequest request = baseRequest("Due today is fine");
        request.setDueDate(LocalDate.now());

        assertDoesNotThrow(() -> taskService.createTask(regularUser, request));
    }

    @Test
    void createTask_adminAssignsToAnotherUser_defaultsToPendingWithAssignedBySet() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(otherUser));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(300L);
            return t;
        });

        TaskRequest request = baseRequest("Assigned task");
        request.setOwnerId(2L); // admin assigns to "bob"
        request.setStatus(TaskStatus.COMPLETED); // attempt to bypass -> must be ignored

        TaskDTO result = taskService.createTask(adminUser, request);

        assertEquals(otherUser.getId(), result.getOwnerId());
        assertEquals(adminUser.getUsername(), result.getAssignedByUsername());
        assertEquals(TaskStatus.PENDING, result.getStatus());
        assertFalse(result.isUnassigned());
    }

    @Test
    void createTask_adminAssignsToDeactivatedUser_throwsBadRequest() {
        otherUser.setActive(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(otherUser));

        TaskRequest request = baseRequest("Should fail");
        request.setOwnerId(2L);

        assertThrows(BadRequestException.class, () -> taskService.createTask(adminUser, request));
    }

    @Test
    void createTask_adminAssignsToAnotherAdmin_throwsBadRequest() {
        when(userRepository.findById(4L)).thenReturn(Optional.of(otherAdmin));

        TaskRequest request = baseRequest("Should fail - can't assign to another admin");
        request.setOwnerId(4L);

        assertThrows(BadRequestException.class, () -> taskService.createTask(adminUser, request));
    }

    @Test
    void createTask_adminCreatesForSelf_defaultsToInProgressAndAssignedByStaysNull() {
        when(userRepository.findById(3L)).thenReturn(Optional.of(adminUser));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(301L);
            return t;
        });

        TaskRequest request = baseRequest("Admin's own task");
        request.setOwnerId(3L); // admin explicitly assigns to themselves

        TaskDTO result = taskService.createTask(adminUser, request);

        assertEquals(adminUser.getId(), result.getOwnerId());
        assertNull(result.getAssignedByUsername());
        assertEquals(TaskStatus.IN_PROGRESS, result.getStatus());
    }

    @Test
    void createTask_adminOmitsAssignment_createsUnassignedTaskWithUnassignedStatus() {
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(302L);
            return t;
        });

        TaskRequest request = baseRequest("Needs an owner later");
        // ownerId left null entirely -> Unassigned, awaiting a later admin assignment.

        TaskDTO result = taskService.createTask(adminUser, request);

        assertTrue(result.isUnassigned());
        assertNull(result.getOwnerId());
        assertNull(result.getAssignedByUsername());
        assertEquals(TaskStatus.UNASSIGNED, result.getStatus());
    }

    @Test
    void createTask_defaultsPriorityToMediumWhenNotProvided() {
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(303L);
            return t;
        });

        TaskRequest request = baseRequest("No priority set");

        TaskDTO result = taskService.createTask(regularUser, request);

        assertEquals(TaskPriority.MEDIUM, result.getPriority());
    }

    // --- quick status update ---

    @Test
    void updateStatus_owner_succeeds() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskDTO result = taskService.updateStatus(regularUser, 100L, TaskStatus.COMPLETED);

        assertEquals(TaskStatus.COMPLETED, result.getStatus());
    }

    @Test
    void updateStatus_nonOwner_throwsAccessDenied() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));

        assertThrows(AccessDeniedException.class,
                () -> taskService.updateStatus(otherUser, 100L, TaskStatus.COMPLETED));
    }

    @Test
    void updateStatus_onUnassignedTask_throwsBadRequest() {
        Task unassignedTask = Task.builder()
                .id(502L).title("Orphan").description("d")
                .status(TaskStatus.UNASSIGNED).priority(TaskPriority.MEDIUM)
                .dueDate(LocalDate.now().plusDays(1)).owner(null).build();
        when(taskRepository.findById(502L)).thenReturn(Optional.of(unassignedTask));

        assertThrows(BadRequestException.class,
                () -> taskService.updateStatus(adminUser, 502L, TaskStatus.IN_PROGRESS));
    }

    // --- admin assign/reassign ---

    @Test
    void assignTask_adminAssignsUnassignedTaskToUser_setsOwnerAssignedByAndPendingStatus() {
        Task unassignedTask = Task.builder()
                .id(400L).title("Orphan task").description("d")
                .status(TaskStatus.UNASSIGNED).priority(TaskPriority.MEDIUM)
                .dueDate(LocalDate.now().plusDays(1)).owner(null).build();

        when(taskRepository.findById(400L)).thenReturn(Optional.of(unassignedTask));
        when(userRepository.findById(1L)).thenReturn(Optional.of(regularUser));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskDTO result = taskService.assignTask(adminUser, 400L, 1L);

        assertFalse(result.isUnassigned());
        assertEquals(regularUser.getId(), result.getOwnerId());
        assertEquals(adminUser.getUsername(), result.getAssignedByUsername());
        assertEquals(TaskStatus.PENDING, result.getStatus());
    }

    @Test
    void assignTask_adminAssignsUnassignedTaskToSelf_setsInProgressAndNoAssignedBy() {
        Task unassignedTask = Task.builder()
                .id(401L).title("Orphan task").description("d")
                .status(TaskStatus.UNASSIGNED).priority(TaskPriority.MEDIUM)
                .dueDate(LocalDate.now().plusDays(1)).owner(null).build();

        when(taskRepository.findById(401L)).thenReturn(Optional.of(unassignedTask));
        when(userRepository.findById(3L)).thenReturn(Optional.of(adminUser));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskDTO result = taskService.assignTask(adminUser, 401L, 3L);

        assertEquals(adminUser.getId(), result.getOwnerId());
        assertNull(result.getAssignedByUsername());
        assertEquals(TaskStatus.IN_PROGRESS, result.getStatus());
    }

    @Test
    void assignTask_reassignAlreadyAssignedTask_leavesStatusUnchanged() {
        // userTask already has status PENDING and an owner (regularUser) - this is a
        // reassignment, not a first-time assignment, so its status should not be touched.
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));
        when(userRepository.findById(2L)).thenReturn(Optional.of(otherUser));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskDTO result = taskService.assignTask(adminUser, 100L, 2L);

        assertEquals(otherUser.getId(), result.getOwnerId());
        assertEquals(TaskStatus.PENDING, result.getStatus()); // unchanged from before
    }

    @Test
    void assignTask_toDeactivatedUser_throwsBadRequest() {
        regularUser.setActive(false);
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));
        when(userRepository.findById(1L)).thenReturn(Optional.of(regularUser));

        assertThrows(BadRequestException.class, () -> taskService.assignTask(adminUser, 100L, 1L));
    }

    @Test
    void assignTask_toAnotherAdmin_throwsBadRequest() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));
        when(userRepository.findById(4L)).thenReturn(Optional.of(otherAdmin));

        assertThrows(BadRequestException.class, () -> taskService.assignTask(adminUser, 100L, 4L));
    }

    // --- delete ---

    @Test
    void deleteTask_nonOwner_throwsAccessDenied() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));

        assertThrows(AccessDeniedException.class, () -> taskService.deleteTask(otherUser, 100L));
    }

    @Test
    void deleteTask_admin_canDeleteAnyTask() {
        when(taskRepository.findById(100L)).thenReturn(Optional.of(userTask));

        assertDoesNotThrow(() -> taskService.deleteTask(adminUser, 100L));
    }
}
