package com.tasktracker.service;

import com.tasktracker.entity.Role;
import com.tasktracker.entity.TaskPriority;
import com.tasktracker.entity.TaskStatus;
import com.tasktracker.entity.User;
import com.tasktracker.repository.TaskRepository;
import com.tasktracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unlike TaskServiceTest (pure Mockito, mocks the repository), this test runs against the
 * real H2 test database and a real Specification query - it exists specifically to catch
 * the class of bug where a filter is wired up on the frontend/controller but silently has
 * no effect because the Specification never actually applies it (exactly what happened with
 * the priority filter: the query param existed but TaskService.buildSpecification never
 * added a predicate for it, so every priority was returned regardless of the filter).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TaskFilteringIntegrationTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User owner;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAll();
        userRepository.deleteAll();

        owner = userRepository.save(User.builder()
                .username("filtertest_" + System.nanoTime())
                .password(passwordEncoder.encode("x"))
                .role(Role.ROLE_USER)
                .active(true)
                .build());

        createTask("Low priority task", TaskPriority.LOW, TaskStatus.IN_PROGRESS, LocalDate.now().plusDays(5));
        createTask("Critical priority task", TaskPriority.CRITICAL, TaskStatus.IN_PROGRESS, LocalDate.now().plusDays(5));
        createTask("Another critical one", TaskPriority.CRITICAL, TaskStatus.COMPLETED, LocalDate.now().minusDays(1));
    }

    private void createTask(String title, TaskPriority priority, TaskStatus status, LocalDate dueDate) {
        taskRepository.save(com.tasktracker.entity.Task.builder()
                .title(title)
                .description("desc")
                .status(status)
                .priority(priority)
                .dueDate(dueDate)
                .owner(owner)
                .build());
    }

    @Test
    void getTasks_filteredByPriority_onlyReturnsMatchingPriority() {
        Page<?> result = taskService.getTasks(
                owner, null, TaskPriority.CRITICAL, null, null, null, null, PageRequest.of(0, 10));

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void getTasks_filteredByPriorityAndStatus_combinesBothFilters() {
        Page<?> result = taskService.getTasks(
                owner, TaskStatus.COMPLETED, TaskPriority.CRITICAL, null, null, null, null, PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void getTasks_noFilters_returnsEverything() {
        Page<?> result = taskService.getTasks(
                owner, null, null, null, null, null, null, PageRequest.of(0, 10));

        assertEquals(3, result.getTotalElements());
    }
}