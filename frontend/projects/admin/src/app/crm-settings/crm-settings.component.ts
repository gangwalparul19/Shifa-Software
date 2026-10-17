import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { ConfirmService } from '../shared/confirm.service';
import { CrmSettingsService } from './crm-settings.service';
import {
  CrmPermission,
  CrmSettings,
  LeadSourceAdmin,
  LeadTagAdmin,
  Pipeline,
  PipelineStage,
} from './crm-settings.model';

type CrmTab = 'pipelines' | 'tags' | 'sources' | 'defaults' | 'permissions';

/**
 * Admin CRM configuration area (crm-expansion Phase 3, Req 16–19): manage
 * pipelines + their stages, lead tags, lead sources, CRM defaults, and the
 * configurable permission layer. Mobile-first, reusing the shared page header,
 * state panel, toasts and confirm dialog. Every write is also enforced
 * server-side; the frontend hides actions the caller lacks via
 * {@code GET /api/me/crm-permissions}.
 */
@Component({
  selector: 'admin-crm-settings',
  imports: [ReactiveFormsModule, PageHeaderComponent, StatePanelComponent],
  templateUrl: './crm-settings.component.html',
  styleUrl: './crm-settings.component.css',
})
export class CrmSettingsComponent implements OnInit {
  private readonly service = inject(CrmSettingsService);
  private readonly fb = inject(FormBuilder);
  private readonly toasts = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly tab = signal<CrmTab>('pipelines');
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  // Data
  protected readonly pipelines = signal<Pipeline[]>([]);
  protected readonly tags = signal<LeadTagAdmin[]>([]);
  protected readonly sources = signal<LeadSourceAdmin[]>([]);
  protected readonly settings = signal<CrmSettings | null>(null);
  protected readonly permissions = signal<CrmPermission[]>([]);
  protected readonly permissionDefs = signal<string[]>([]);
  protected readonly myPermissions = signal<string[]>([]);

  // Which pipeline is expanded for stage editing.
  protected readonly expandedPipeline = signal<number | null>(null);

  protected readonly terminalKinds = ['', 'WON', 'LOST', 'CLOSED'];
  protected readonly assignmentRules = ['DIRECT', 'ROUND_ROBIN'];

  // Forms
  protected readonly pipelineForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(80)]],
  });
  protected readonly stageForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(60)]],
    terminalKind: [''],
    isEntry: [false],
  });
  protected readonly tagForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(40)]],
    color: [''],
    stageId: [null as number | null],
  });
  protected readonly sourceForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(40)]],
  });
  protected readonly defaultsForm = this.fb.nonNullable.group({
    followUpDefaultTime: [''],
    missedGraceHours: [24, [Validators.required, Validators.min(1), Validators.max(720)]],
    defaultPipelineId: [null as number | null],
    defaultAssignmentRule: ['DIRECT'],
  });
  protected readonly permForm = this.fb.nonNullable.group({
    permission: ['', [Validators.required]],
    role: ['ADMIN'],
    granted: [true],
  });

  protected readonly saving = signal(false);

  /** All stages flattened for the tag stage-association picker. */
  protected readonly allStages = computed<{ id: number; label: string }[]>(() =>
    this.pipelines().flatMap((p) =>
      p.stages.map((s) => ({ id: s.id, label: `${p.name} · ${s.name}` })),
    ),
  );

  ngOnInit(): void {
    this.loadAll();
  }

  setTab(tab: CrmTab): void {
    this.tab.set(tab);
  }

  canManage(perm: string): boolean {
    // ADMIN always holds everything; the backend confirms. Hide only when the
    // effective set is loaded and lacks the permission.
    const mine = this.myPermissions();
    return mine.length === 0 ? true : mine.includes(perm);
  }

  loadAll(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.myPermissions().subscribe({
      next: (perms) => this.myPermissions.set(perms),
      error: () => this.myPermissions.set([]),
    });
    this.service.listPipelines().subscribe({
      next: (rows) => {
        this.pipelines.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load CRM configuration. Please try again.');
        this.loading.set(false);
      },
    });
    this.service.listTags().subscribe({ next: (r) => this.tags.set(r), error: () => {} });
    this.service.listSources().subscribe({ next: (r) => this.sources.set(r), error: () => {} });
    this.service.getSettings().subscribe({
      next: (s) => {
        this.settings.set(s);
        this.defaultsForm.patchValue({
          followUpDefaultTime: s.followUpDefaultTime?.slice(0, 5) ?? '',
          missedGraceHours: s.missedGraceHours,
          defaultPipelineId: s.defaultPipelineId,
          defaultAssignmentRule: s.defaultAssignmentRule,
        });
      },
      error: () => {},
    });
    this.service.permissionDefinitions().subscribe({
      next: (d) => {
        this.permissionDefs.set(d);
        if (d.length && !this.permForm.controls.permission.value) {
          this.permForm.patchValue({ permission: d[0] });
        }
      },
      error: () => {},
    });
    this.service.listPermissions().subscribe({ next: (p) => this.permissions.set(p), error: () => {} });
  }

  // --- Pipelines ----------------------------------------------------------

  toggleExpand(id: number): void {
    this.expandedPipeline.update((cur) => (cur === id ? null : id));
  }

  createPipeline(): void {
    if (this.pipelineForm.invalid || this.saving()) {
      this.pipelineForm.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.service.createPipeline({ name: this.pipelineForm.getRawValue().name.trim() }).subscribe({
      next: (p) => {
        this.pipelines.update((list) => [...list, p]);
        this.pipelineForm.reset({ name: '' });
        this.saving.set(false);
        this.toasts.success('Pipeline created');
      },
      error: (e) => this.fail(e, 'Could not create the pipeline.'),
    });
  }

  togglePipelineActive(p: Pipeline): void {
    this.service.updatePipeline(p.id, { name: p.name, active: !p.active }).subscribe({
      next: (updated) => this.replacePipeline(updated),
      error: (e) => this.fail(e, 'Could not update the pipeline.'),
    });
  }

  addStage(p: Pipeline): void {
    if (this.stageForm.invalid) {
      this.stageForm.markAllAsTouched();
      return;
    }
    const v = this.stageForm.getRawValue();
    this.service
      .addStage(p.id, {
        name: v.name.trim(),
        terminalKind: v.terminalKind || null,
        isEntry: v.isEntry,
      })
      .subscribe({
        next: () => {
          this.stageForm.reset({ name: '', terminalKind: '', isEntry: false });
          this.refreshPipeline(p.id);
          this.toasts.success('Stage added');
        },
        error: (e) => this.fail(e, 'Could not add the stage.'),
      });
  }

  toggleStageActive(p: Pipeline, s: PipelineStage): void {
    if (s.active) {
      this.service.retireStage(p.id, s.id).subscribe({
        next: () => this.refreshPipeline(p.id),
        error: (e) => this.fail(e, 'Could not retire the stage.'),
      });
    } else {
      this.service
        .updateStage(p.id, s.id, { name: s.name, terminalKind: s.terminalKind, active: true })
        .subscribe({
          next: () => this.refreshPipeline(p.id),
          error: (e) => this.fail(e, 'Could not reactivate the stage.'),
        });
    }
  }

  makeEntry(p: Pipeline, s: PipelineStage): void {
    this.service
      .updateStage(p.id, s.id, { name: s.name, terminalKind: s.terminalKind, isEntry: true })
      .subscribe({
        next: () => this.refreshPipeline(p.id),
        error: (e) => this.fail(e, 'Could not set the entry stage.'),
      });
  }

  moveStage(p: Pipeline, index: number, delta: number): void {
    const ids = p.stages.map((s) => s.id);
    const target = index + delta;
    if (target < 0 || target >= ids.length) {
      return;
    }
    [ids[index], ids[target]] = [ids[target], ids[index]];
    this.service.reorderStages(p.id, ids).subscribe({
      next: () => this.refreshPipeline(p.id),
      error: (e) => this.fail(e, 'Could not reorder the stages.'),
    });
  }

  private refreshPipeline(id: number): void {
    this.service.listPipelines().subscribe({ next: (rows) => this.pipelines.set(rows) });
  }

  private replacePipeline(updated: Pipeline): void {
    this.pipelines.update((list) => list.map((p) => (p.id === updated.id ? updated : p)));
  }

  // --- Tags ---------------------------------------------------------------

  createTag(): void {
    if (this.tagForm.invalid || this.saving()) {
      this.tagForm.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const v = this.tagForm.getRawValue();
    this.service
      .createTag({ name: v.name.trim(), color: v.color || null, stageId: v.stageId })
      .subscribe({
        next: (t) => {
          this.tags.update((list) => [...list, t]);
          this.tagForm.reset({ name: '', color: '', stageId: null });
          this.saving.set(false);
          this.toasts.success('Tag created');
        },
        error: (e) => this.fail(e, 'Could not create the tag.'),
      });
  }

  toggleTagActive(t: LeadTagAdmin): void {
    if (t.active) {
      this.service.retireTag(t.id).subscribe({
        next: (u) => this.replaceTag(u),
        error: (e) => this.fail(e, 'Could not retire the tag.'),
      });
    } else {
      this.service
        .updateTag(t.id, { name: t.name, color: t.color, active: true, stageId: t.stageId })
        .subscribe({
          next: (u) => this.replaceTag(u),
          error: (e) => this.fail(e, 'Could not reactivate the tag.'),
        });
    }
  }

  private replaceTag(updated: LeadTagAdmin): void {
    this.tags.update((list) => list.map((t) => (t.id === updated.id ? updated : t)));
  }

  // --- Sources ------------------------------------------------------------

  createSource(): void {
    if (this.sourceForm.invalid || this.saving()) {
      this.sourceForm.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.service.createSource({ name: this.sourceForm.getRawValue().name.trim() }).subscribe({
      next: (s) => {
        this.sources.update((list) => [...list, s]);
        this.sourceForm.reset({ name: '' });
        this.saving.set(false);
        this.toasts.success('Source created');
      },
      error: (e) => this.fail(e, 'Could not create the source.'),
    });
  }

  toggleSourceActive(s: LeadSourceAdmin): void {
    this.service.updateSource(s.id, { name: s.name, active: !s.active }).subscribe({
      next: (u) => this.sources.update((list) => list.map((it) => (it.id === s.id ? u : it))),
      error: (e) => this.fail(e, 'Could not update the source.'),
    });
  }

  // --- Defaults -----------------------------------------------------------

  saveDefaults(): void {
    if (this.defaultsForm.invalid || this.saving()) {
      this.defaultsForm.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const v = this.defaultsForm.getRawValue();
    this.service
      .updateSettings({
        followUpDefaultTime: v.followUpDefaultTime ? `${v.followUpDefaultTime}:00` : null,
        missedGraceHours: v.missedGraceHours,
        defaultPipelineId: v.defaultPipelineId,
        defaultAssignmentRule: v.defaultAssignmentRule,
      })
      .subscribe({
        next: (s) => {
          this.settings.set(s);
          this.saving.set(false);
          this.toasts.success('CRM settings saved');
        },
        error: (e) => this.fail(e, 'Could not save the CRM settings.'),
      });
  }

  // --- Permissions --------------------------------------------------------

  grantPermission(): void {
    if (this.permForm.invalid || this.saving()) {
      this.permForm.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const v = this.permForm.getRawValue();
    this.service
      .grantPermission({ permission: v.permission, role: v.role, granted: v.granted })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.service.listPermissions().subscribe({ next: (p) => this.permissions.set(p) });
          this.toasts.success('Permission updated');
        },
        error: (e) => this.fail(e, 'Could not update the permission.'),
      });
  }

  async revokePermission(p: CrmPermission): Promise<void> {
    const ok = await this.confirm.confirm({
      title: 'Remove grant',
      message: 'This reverts the role/user to the default. Continue?',
      confirmLabel: 'Remove',
      icon: 'ti-trash',
      danger: true,
    });
    if (!ok) {
      return;
    }
    this.service.revokePermission(p.id).subscribe({
      next: () => this.permissions.update((list) => list.filter((it) => it.id !== p.id)),
      error: (e) => this.fail(e, 'Could not remove the grant.'),
    });
  }

  private fail(error: unknown, fallback: string): void {
    this.saving.set(false);
    const message =
      (error as { error?: { message?: string } })?.error?.message ?? fallback;
    this.toasts.error(message);
  }
}
