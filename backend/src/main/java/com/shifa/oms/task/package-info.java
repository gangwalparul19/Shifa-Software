/**
 * CRM to-do tasks (CRM expansion Phase 2, Task 10, design &sect;2.2). A task is
 * owned by a CRM_User, optionally linked to a lead/customer, with a due
 * date/time, priority, and completion state (Req 8). Owner-scoped reads/writes
 * (own / team / all via {@link com.shifa.oms.auth.SalespersonScopeResolver});
 * due reminders via {@link com.shifa.oms.task.TaskReminderJob} on the existing
 * outbox + notification rails.
 */
package com.shifa.oms.task;
