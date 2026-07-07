import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { SortState } from 'core';
import { AuditService } from './audit.service';
import { AuditEntry } from './audit.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';

/** Sort fields the backend accepts for the audit log. */
const SORT_FIELDS = new Set(['createdAt', 'action', 'entityType', 'actorUsername']);
const TABLE_KEY = 'audit';

/**
 * Admin Audit log (Set B — Feature 4).
 *
 * <p>Responsive, paginated, filterable table of actor / action / entity /
 * summary / timestamp rows (newest first). Filters: action, entity type,
 * free-text search and a date range.
 */
@Component({
  selector: 'admin-audit',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    PageHeaderComponent,
    StatePanelComponent,
    DensityToggleComponent,
    PaginationComponent,
    SortableHeaderComponent,
  ],
  templateUrl: './audit.component.html',
  styleUrl: './audit.component.css',
})
export class AuditComponent implements OnInit, OnDestroy {
  private readonly service = inject(AuditService);

  protected readonly entries = signal<AuditEntry[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** Distinct actions / entity types seen so far, for the filter dropdowns. */
  protected readonly actions = signal<string[]>([]);
  protected readonly entityTypes = signal<string[]>([]);

  // --- Paging + sort ------------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize(TABLE_KEY, 20));
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly sort = signal<SortState>({ field: 'createdAt', dir: 'desc' });

  // --- Filters ------------------------------------------------------------
  protected readonly search = new FormControl<string>('', { nonNullable: true });
  protected readonly filters = new FormGroup({
    action: new FormControl<string>('', { nonNullable: true }),
    entityType: new FormControl<string>('', { nonNullable: true }),
    from: new FormControl<string>('', { nonNullable: true }),
    to: new FormControl<string>('', { nonNullable: true }),
  });

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.load();
    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.resetAndLoad());
    this.filters.valueChanges.pipe(takeUntil(this.destroy$)).subscribe(() => this.resetAndLoad());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const f = this.filters.getRawValue();
    this.service
      .page({
        action: f.action || null,
        entityType: f.entityType || null,
        q: this.search.value,
        from: f.from || null,
        to: f.to || null,
        page: this.page(),
        size: this.size(),
        sort: sortParam(this.sort()),
      })
      .subscribe({
        next: (res) => {
          this.entries.set(res.content);
          this.totalPages.set(res.totalPages);
          this.totalElements.set(res.totalElements);
          this.mergeFacets(res.content);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set('Could not load the audit log. Please try again.');
          this.loading.set(false);
        },
      });
  }

  private mergeFacets(rows: AuditEntry[]): void {
    const acts = new Set(this.actions());
    const ents = new Set(this.entityTypes());
    rows.forEach((r) => {
      if (r.action) acts.add(r.action);
      if (r.entityType) ents.add(r.entityType);
    });
    this.actions.set([...acts].sort());
    this.entityTypes.set([...ents].sort());
  }

  private resetAndLoad(): void {
    this.page.set(0);
    this.load();
  }

  goToPage(page: number): void {
    this.page.set(page);
    this.load();
  }

  setSize(size: number): void {
    this.size.set(size);
    writePageSize(TABLE_KEY, size);
    this.resetAndLoad();
  }

  onSort(field: string): void {
    if (!SORT_FIELDS.has(field)) {
      return;
    }
    this.sort.set(toggleSort(this.sort(), field));
    this.resetAndLoad();
  }

  clearSearch(): void {
    this.search.setValue('');
  }

  clearFilters(): void {
    this.filters.reset({ action: '', entityType: '', from: '', to: '' });
    this.search.setValue('');
  }

  hasFilters(): boolean {
    const f = this.filters.getRawValue();
    return !!(this.search.value || f.action || f.entityType || f.from || f.to);
  }

  humanize(value: string): string {
    return value
      .replaceAll('_', ' ')
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }
}
