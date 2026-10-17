package com.shifa.oms.task;

import com.shifa.oms.activity.ActivityService;
import com.shifa.oms.activity.ActivityTypes;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.task.dto.CreateTaskRequest;
import com.shifa.oms.task.dto.TaskResponse;
import com.shifa.oms.task.dto.UpdateTaskRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CRM task application service (CRM expansion Phase 2, Task 10, design
 * &sect;2.2). Create / update / complete, owner-scoped: a SALESPERSON sees and
 * mutates only their own tasks, a TEAM_LEAD sees their team's (+ own), and an
 * ADMIN sees all (Req 8.3) — all via {@link SalespersonScopeResolver#creatorScope}.
 * Each lifecycle change records a {@link ActivityService} entry (Req 8.2, 11.1);
 * due reminders are delivered by {@link TaskReminderJob}.
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final ActivityService activityService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskService(TaskRepository taskRepository,
                       SalespersonScopeResolver scopeResolver,
                       ActivityService activityService) {
        this(taskRepository, scopeResolver, activityService, Clock.systemDefaultZone());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    TaskService(TaskRepository taskRepository,
                SalespersonScopeResolver scopeResolver,
                ActivityService activityService,
                Clock clock) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.activityService = activityService;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Owner-scoped task list, optional {@code status} filter (Req 8.3, 8.5). A
     * SALESPERSON sees only their own tasks; a TEAM_LEAD their team's; an ADMIN
     * all.
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> list(TaskStatus status, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        List<Long> ownerIds = scopeResolver.creatorScope(actor).orElse(null);
        return taskRepository.findScoped(ownerIds, status).stream()
                .map(TaskResponse::from)
                .toList();
    }

    /**
     * Creates a task (Req 8.1). The owner is the acting user unless an
     * ADMIN/TEAM_LEAD supplies {@code ownerUserId} (to create it for a team
     * member); a SALESPERSON may only create their own.
     */
    @Transactional
    public TaskResponse create(CreateTaskRequest request, AuthPrincipal actor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        if (request.title() == null || request.title().isBlank()) {
            throw new ValidationException("title is required.");
        }
        Long owner = resolveOwner(request.ownerUserId(), actor);
        TaskEntity task = new TaskEntity(owner, request.title().trim());
        task.setDescription(blankToNull(request.description()));
        task.setLeadId(request.leadId());
        task.setCustomerMobile(blankToNull(request.customerMobile()));
        task.setDueAt(request.dueAt());
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        TaskEntity saved = taskRepository.save(task);
        recordActivity(ActivityTypes.TASK_CREATED, saved, actor, "Task created: " + saved.getTitle());
        return TaskResponse.from(saved);
    }

    /** Updates an owned task's mutable fields (Req 8.2). */
    @Transactional
    public TaskResponse update(Long id, UpdateTaskRequest request, AuthPrincipal actor) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actor, "actor");
        if (request.title() == null || request.title().isBlank()) {
            throw new ValidationException("title is required.");
        }
        TaskEntity task = loadScoped(id, actor);
        task.setTitle(request.title().trim());
        task.setDescription(blankToNull(request.description()));
        task.setLeadId(request.leadId());
        task.setCustomerMobile(blankToNull(request.customerMobile()));
        task.setDueAt(request.dueAt());
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        TaskEntity saved = taskRepository.save(task);
        recordActivity(ActivityTypes.TASK_UPDATED, saved, actor, "Task updated: " + saved.getTitle());
        return TaskResponse.from(saved);
    }

    /** Marks an owned task complete, recording the completion time (Req 8.2). */
    @Transactional
    public TaskResponse complete(Long id, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        TaskEntity task = loadScoped(id, actor);
        if (task.getStatus() != TaskStatus.DONE) {
            task.setStatus(TaskStatus.DONE);
            task.setCompletedAt(LocalDateTime.now(clock));
        }
        TaskEntity saved = taskRepository.save(task);
        recordActivity(ActivityTypes.TASK_DONE, saved, actor, "Task completed: " + saved.getTitle());
        return TaskResponse.from(saved);
    }

    // --- Internal helpers ---------------------------------------------------

    private Long resolveOwner(Long requestedOwner, AuthPrincipal actor) {
        if (requestedOwner == null || requestedOwner.equals(actor.userId())) {
            return actor.userId();
        }
        // Only ADMIN / TEAM_LEAD may create a task for a different owner.
        if (actor.role() != Role.ADMIN && actor.role() != Role.TEAM_LEAD) {
            throw new AccessDeniedException("You may only create tasks for yourself.");
        }
        // A team lead may only assign within their scope.
        if (actor.role() == Role.TEAM_LEAD) {
            List<Long> scope = scopeResolver.creatorScope(actor).orElse(List.of());
            if (!scope.contains(requestedOwner)) {
                throw new AccessDeniedException("You may only create tasks for your team.");
            }
        }
        return requestedOwner;
    }

    /** Loads a task, enforcing owner scoping with a 404 when out of scope. */
    private TaskEntity loadScoped(Long id, AuthPrincipal actor) {
        TaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task " + id + " does not exist."));
        Optional<List<Long>> scope = scopeResolver.creatorScope(actor);
        if (scope.isPresent() && !scope.get().contains(task.getOwnerUserId())) {
            // Present-but-out-of-scope → 404 (do not reveal the task exists).
            throw new ResourceNotFoundException("Task " + id + " does not exist.");
        }
        return task;
    }

    private void recordActivity(String type, TaskEntity task, AuthPrincipal actor, String summary) {
        if (activityService == null || task.getLeadId() == null && task.getCustomerMobile() == null) {
            return;
        }
        activityService.record(type, task.getLeadId(), task.getCustomerMobile(), actor, summary, null);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
