import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { TeamLead, TeamMember, TeamService } from './team.service';

/**
 * ADMIN-only team management (product feature: Team Lead role). Lets an admin
 * assign each salesperson to a team lead, which drives the team-scoped order
 * visibility a TEAM_LEAD gets over their team's orders.
 *
 * <p>Mobile-first: a summary strip of team leads (with member counts) over a
 * list of salesperson cards, each with an inline "reports to" picker that
 * assigns/clears the team lead via {@code PUT /api/admin/team/salespeople/{id}}.
 */
@Component({
  selector: 'admin-team',
  imports: [FormsModule, RouterLink, PageHeaderComponent, StatePanelComponent],
  template: `
    <admin-page-header
      title="Teams"
      subtitle="Assign salespeople to a team lead so leads can track their team's orders."
      [breadcrumbs]="[{ label: 'Teams' }]"
    />

    @if (loading()) {
      <admin-state-panel variant="loading" [card]="true" loadingLabel="Loading teams…" />
    } @else if (loadError()) {
      <admin-state-panel variant="error" [card]="true" [message]="loadError()!" (retry)="load()" />
    } @else {
      @if (leads().length === 0) {
        <div class="alert alert-info" role="status">
          <i class="ti ti-info-circle me-1"></i>
          No team leads yet. Create a user with the <strong>Team Lead</strong> role on the
          <a routerLink="/users">Users</a> page, then assign salespeople to them here.
        </div>
      } @else {
        <!-- Team lead summary strip -->
        <div class="row g-2 mb-3">
          @for (lead of leads(); track lead.id) {
            <div class="col-6 col-md-4 col-lg-3">
              <div class="card h-100">
                <div class="card-body py-2">
                  <div class="fw-medium text-truncate">{{ lead.fullName }}</div>
                  <div class="text-secondary small">
                    {{ lead.memberCount }} salesperson{{ lead.memberCount === 1 ? '' : 's' }}
                    @if (!lead.active) { · <span class="text-danger">inactive</span> }
                  </div>
                </div>
              </div>
            </div>
          }
        </div>
      }

      <!-- Salespeople with their assignment -->
      <div class="card">
        <div class="card-header py-2">
          <span class="fw-medium">Salespeople</span>
          <span class="text-secondary small ms-2">{{ members().length }}</span>
        </div>
        @if (members().length === 0) {
          <div class="card-body text-secondary">No salespeople found.</div>
        } @else {
          <div class="list-group list-group-flush">
            @for (m of members(); track m.id) {
              <div class="list-group-item d-flex flex-wrap align-items-center gap-2">
                <div class="flex-fill" style="min-width: 0">
                  <div class="fw-medium text-truncate">
                    {{ m.fullName }}
                    @if (!m.active) { <span class="badge tone-grey ms-1">inactive</span> }
                  </div>
                  <div class="text-secondary small text-truncate">{{ '@' + m.username }}</div>
                </div>
                <div class="d-flex align-items-center gap-2">
                  <label class="form-label small mb-0 text-secondary d-none d-sm-inline">Reports to</label>
                  <select
                    class="form-select form-select-sm"
                    style="min-width: 12rem"
                    [ngModel]="m.teamLeadId ?? ''"
                    (ngModelChange)="onAssign(m, $event)"
                    [disabled]="savingId() === m.id || leads().length === 0"
                    [attr.aria-label]="'Assign team lead for ' + m.fullName"
                  >
                    <option value="">— Unassigned —</option>
                    @for (lead of leads(); track lead.id) {
                      <option [value]="lead.id">{{ lead.fullName }}</option>
                    }
                  </select>
                  @if (savingId() === m.id) {
                    <span class="spinner-border spinner-border-sm text-secondary" role="status" aria-hidden="true"></span>
                  }
                </div>
              </div>
            }
          </div>
        }
      </div>
    }
  `,
})
export class TeamComponent implements OnInit {
  private readonly service = inject(TeamService);
  private readonly toasts = inject(ToastService);

  protected readonly leads = signal<TeamLead[]>([]);
  protected readonly members = signal<TeamMember[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly savingId = signal<number | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    let pending = 2;
    const done = () => {
      if (--pending === 0) {
        this.loading.set(false);
      }
    };
    this.service.leads().subscribe({
      next: (rows) => {
        this.leads.set(rows);
        done();
      },
      error: () => {
        this.loadError.set('Could not load teams. Please try again.');
        this.loading.set(false);
      },
    });
    this.service.salespeople().subscribe({
      next: (rows) => {
        this.members.set(rows);
        done();
      },
      error: () => {
        this.loadError.set('Could not load teams. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** Assigns (or clears when the value is empty) a salesperson's team lead. */
  onAssign(member: TeamMember, value: string | number): void {
    const teamLeadId = value === '' || value === null ? null : Number(value);
    if (teamLeadId === member.teamLeadId) {
      return;
    }
    this.savingId.set(member.id);
    this.service.assign(member.id, teamLeadId).subscribe({
      next: () => {
        this.savingId.set(null);
        const leadName = teamLeadId
          ? this.leads().find((l) => l.id === teamLeadId)?.fullName ?? null
          : null;
        this.members.update((rows) =>
          rows.map((r) => (r.id === member.id ? { ...r, teamLeadId, teamLeadName: leadName } : r)),
        );
        // Refresh lead member counts.
        this.service.leads().subscribe({ next: (rows) => this.leads.set(rows) });
        this.toasts.success(
          teamLeadId ? `${member.fullName} assigned to ${leadName}.` : `${member.fullName} unassigned.`,
        );
      },
      error: () => {
        this.savingId.set(null);
        this.toasts.error('Could not update the assignment. Please try again.');
      },
    });
  }
}
