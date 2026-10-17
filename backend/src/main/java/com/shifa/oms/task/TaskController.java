package com.shifa.oms.task;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.task.dto.CreateTaskRequest;
import com.shifa.oms.task.dto.TaskResponse;
import com.shifa.oms.task.dto.UpdateTaskRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CRM task REST surface (CRM expansion Phase 2, Task 10; Req 8). Open to
 * {@code SALESPERSON} / {@code TEAM_LEAD} / {@code ADMIN}; owner scoping (own /
 * team / all) is enforced inside {@link TaskService}, never trusted from the
 * client.
 */
@RestController
@RequestMapping("/api/tasks")
@PreAuthorize("hasAnyRole('SALESPERSON','TEAM_LEAD','ADMIN')")
public class TaskController {

    private final TaskService taskService;
    private final CurrentUserService currentUserService;

    public TaskController(TaskService taskService, CurrentUserService currentUserService) {
        this.taskService = taskService;
        this.currentUserService = currentUserService;
    }

    /** The caller's (scoped) tasks, optional status filter (Req 8.3, 8.5). */
    @GetMapping
    public List<TaskResponse> list(@RequestParam(name = "status", required = false) TaskStatus status) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return taskService.list(status, actor);
    }

    /** Create a task (Req 8.1). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@Valid @RequestBody CreateTaskRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return taskService.create(request, actor);
    }

    /** Update an owned task (Req 8.2). */
    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable Long id, @Valid @RequestBody UpdateTaskRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return taskService.update(id, request, actor);
    }

    /** Mark an owned task complete (Req 8.2). */
    @PostMapping("/{id}/complete")
    public TaskResponse complete(@PathVariable Long id) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return taskService.complete(id, actor);
    }
}
