import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError, AuthService, Role } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { LedgerService } from './ledger.service';
import {
  ACCOUNT_NATURES,
  AccountGroup,
  AccountNature,
  LedgerAccount,
  natureLabel,
  naturePillClass,
} from './ledger.model';

/**
 * A node in the rendered Chart-of-Accounts tree: an account group with its
 * child groups (recursively) and the ledger accounts posted directly under it.
 */
interface GroupNode {
  group: AccountGroup;
  depth: number;
  children: GroupNode[];
  ledgers: LedgerAccount[];
}

/**
 * Chart of Accounts (Reqs 1.1, 1.2, 2.1, 16.3, 16.4).
 *
 * <p>Renders the hierarchical account-group → ledger tree (grouped by
 * {@code parentGroupId}, with each ledger's derived {@link AccountNature} shown
 * as a pill). ADMIN and ACCOUNTANT see inline "Add group" / "Add ledger" forms
 * and a delete action on unused ledgers; a CA sees the tree read-only — every
 * mutating affordance is hidden ({@link canManage}). The backend enforces the
 * same rule, so the UI gate is purely a convenience.
 */
@Component({
  selector: 'admin-chart-of-accounts',
  standalone: true,
  imports: [ReactiveFormsModule, PageHeaderComponent, StatePanelComponent],
  templateUrl: './chart-of-accounts.component.html',
  styleUrl: './chart-of-accounts.component.css',
})
export class ChartOfAccountsComponent implements OnInit {
  private readonly ledger = inject(LedgerService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  /** Nature pill helper exposed to the template. */
  protected readonly naturePillClass = naturePillClass;
  protected readonly natureLabel = natureLabel;
  protected readonly natures = ACCOUNT_NATURES;

  /**
   * Whether the signed-in user may edit the Chart of Accounts. Only ADMIN and
   * ACCOUNTANT can add groups/ledgers or delete a ledger; a CA gets a read-only
   * view (Reqs 16.3, 16.4). The backend enforces the same rule.
   */
  protected readonly canManage = computed(() => this.auth.hasAnyRole(Role.ADMIN, Role.ACCOUNTANT));

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly groups = signal<AccountGroup[]>([]);
  protected readonly ledgers = signal<LedgerAccount[]>([]);
  protected readonly saving = signal(false);

  /** The tree of root groups (each carrying nested children + its ledgers). */
  protected readonly tree = computed<GroupNode[]>(() => this.buildTree(this.groups(), this.ledgers()));

  // --- Add-group form -----------------------------------------------------
  /** Whether the "Add group" inline form is open. */
  protected readonly groupFormOpen = signal(false);
  protected readonly groupForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    // A root group needs a nature; a child group inherits its parent's nature.
    parentGroupId: this.fb.control<number | null>(null),
    nature: this.fb.control<AccountNature | null>('ASSET'),
  });

  // --- Add-ledger form ----------------------------------------------------
  /** Whether the "Add ledger" inline form is open. */
  protected readonly ledgerFormOpen = signal(false);
  protected readonly ledgerForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    accountGroupId: this.fb.control<number | null>(null, Validators.required),
  });

  ngOnInit(): void {
    this.load();
  }

  /** Loads the account groups and ledgers together. */
  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    // Load groups first (drives the tree + the parent/group pickers), then ledgers.
    this.ledger.listAccountGroups().subscribe({
      next: (groups) => {
        this.groups.set(groups);
        this.ledger.listLedgers().subscribe({
          next: (ledgers) => {
            this.ledgers.set(ledgers);
            this.loading.set(false);
          },
          error: (err: HttpErrorResponse) => {
            this.loadError.set(this.messageOf(err) ?? 'Could not load the chart of accounts.');
            this.loading.set(false);
          },
        });
      },
      error: (err: HttpErrorResponse) => {
        this.loadError.set(this.messageOf(err) ?? 'Could not load the chart of accounts.');
        this.loading.set(false);
      },
    });
  }

  // --- Add group ----------------------------------------------------------

  /** Opens the "Add group" form (defaulting to a root group under a nature). */
  openGroupForm(): void {
    this.groupForm.reset({ name: '', parentGroupId: null, nature: 'ASSET' });
    this.groupFormOpen.set(true);
  }

  closeGroupForm(): void {
    this.groupFormOpen.set(false);
  }

  submitGroup(): void {
    if (!this.canManage() || this.saving()) {
      return;
    }
    if (this.groupForm.invalid) {
      this.groupForm.markAllAsTouched();
      return;
    }
    const { name, parentGroupId, nature } = this.groupForm.getRawValue();
    // A child group inherits its parent's nature (Req 1.3); only send a nature
    // for a root group.
    this.saving.set(true);
    this.ledger
      .createAccountGroup({
        name: name.trim(),
        parentGroupId: parentGroupId ?? null,
        nature: parentGroupId ? null : nature,
      })
      .subscribe({
        next: (created) => {
          this.groups.update((list) => [...list, created]);
          this.saving.set(false);
          this.groupFormOpen.set(false);
          this.toasts.success(`Group “${created.name}” added.`);
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toasts.error(this.messageOf(err) ?? 'Could not add the group.');
        },
      });
  }

  // --- Add ledger ---------------------------------------------------------

  /** Opens the "Add ledger" form, optionally pre-selecting a group. */
  openLedgerForm(groupId?: number): void {
    this.ledgerForm.reset({ name: '', accountGroupId: groupId ?? null });
    this.ledgerFormOpen.set(true);
  }

  closeLedgerForm(): void {
    this.ledgerFormOpen.set(false);
  }

  submitLedger(): void {
    if (!this.canManage() || this.saving()) {
      return;
    }
    if (this.ledgerForm.invalid) {
      this.ledgerForm.markAllAsTouched();
      return;
    }
    const { name, accountGroupId } = this.ledgerForm.getRawValue();
    this.saving.set(true);
    this.ledger.createLedger({ name: name.trim(), accountGroupId: accountGroupId! }).subscribe({
      next: (created) => {
        this.ledgers.update((list) => [...list, created]);
        this.saving.set(false);
        this.ledgerFormOpen.set(false);
        this.toasts.success(`Ledger “${created.name}” added.`);
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.toasts.error(this.messageOf(err) ?? 'Could not add the ledger.');
      },
    });
  }

  // --- Delete ledger ------------------------------------------------------

  /** Deletes an unused ledger after confirmation (rejected server-side if referenced). */
  async deleteLedger(ledger: LedgerAccount): Promise<void> {
    if (!this.canManage()) {
      return;
    }
    const ok = await this.confirm.confirm({
      title: 'Delete ledger',
      message: `Delete the ledger “${ledger.name}”? This is only allowed when it has no posted vouchers.`,
      confirmLabel: 'Delete',
      danger: true,
      icon: 'ti-trash',
    });
    if (!ok) {
      return;
    }
    this.ledger.deleteLedger(ledger.id).subscribe({
      next: () => {
        this.ledgers.update((list) => list.filter((l) => l.id !== ledger.id));
        this.toasts.success(`Ledger “${ledger.name}” deleted.`);
      },
      error: (err: HttpErrorResponse) => {
        this.toasts.error(this.messageOf(err) ?? 'Could not delete the ledger.');
      },
    });
  }

  // --- Helpers ------------------------------------------------------------

  /**
   * Builds the group hierarchy (root groups → nested children) and attaches each
   * group's direct ledger accounts (sorted by name). Groups are ordered by
   * nature then name; any orphaned group (missing parent) is treated as a root
   * so nothing is silently dropped.
   */
  private buildTree(groups: AccountGroup[], ledgers: LedgerAccount[]): GroupNode[] {
    const byParent = new Map<number | null, AccountGroup[]>();
    const ids = new Set(groups.map((g) => g.id));
    for (const g of groups) {
      const parent = g.parentGroupId !== null && ids.has(g.parentGroupId) ? g.parentGroupId : null;
      const bucket = byParent.get(parent) ?? [];
      bucket.push(g);
      byParent.set(parent, bucket);
    }
    const ledgersByGroup = new Map<number, LedgerAccount[]>();
    for (const l of ledgers) {
      const bucket = ledgersByGroup.get(l.accountGroupId) ?? [];
      bucket.push(l);
      ledgersByGroup.set(l.accountGroupId, bucket);
    }

    const sortGroups = (a: AccountGroup, b: AccountGroup): number =>
      a.nature === b.nature ? a.name.localeCompare(b.name) : a.nature.localeCompare(b.nature);
    const sortLedgers = (a: LedgerAccount, b: LedgerAccount): number => a.name.localeCompare(b.name);

    const build = (group: AccountGroup, depth: number): GroupNode => ({
      group,
      depth,
      children: (byParent.get(group.id) ?? []).sort(sortGroups).map((child) => build(child, depth + 1)),
      ledgers: (ledgersByGroup.get(group.id) ?? []).sort(sortLedgers),
    });

    return (byParent.get(null) ?? []).sort(sortGroups).map((root) => build(root, 0));
  }

  /**
   * Flattens the tree to a depth-first render list so the template can iterate a
   * single array (each row carries its indentation depth).
   */
  protected readonly flatNodes = computed<GroupNode[]>(() => {
    const out: GroupNode[] = [];
    const walk = (nodes: GroupNode[]): void => {
      for (const node of nodes) {
        out.push(node);
        walk(node.children);
      }
    };
    walk(this.tree());
    return out;
  });

  /** Indentation style for a node/ledger at the given depth. */
  protected indentStyle(depth: number): string {
    return `${depth * 1.25}rem`;
  }

  private messageOf(err: HttpErrorResponse): string | null {
    const body = err.error as ApiError | undefined;
    return body?.message ?? null;
  }
}
