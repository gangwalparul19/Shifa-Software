import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { ConfirmService } from '../shared/confirm.service';
import { AnnouncementsService } from './announcements.service';
import {
  Announcement,
  AnnouncementSeverity,
  announcementAlertClass,
  announcementIcon,
} from './announcements.model';

/**
 * Admin management of staff announcement banners (FEATURE-ROADMAP §8.4): post a
 * notice, toggle it active/inactive, or delete it. Active announcements appear as
 * a banner for every signed-in staff member (rendered by the app shell).
 */
@Component({
  selector: 'admin-announcements',
  imports: [ReactiveFormsModule, DatePipe, PageHeaderComponent, StatePanelComponent],
  templateUrl: './announcements.component.html',
  styleUrl: './announcements.component.css',
})
export class AnnouncementsComponent implements OnInit {
  private readonly service = inject(AnnouncementsService);
  private readonly fb = inject(FormBuilder);
  private readonly toasts = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly items = signal<Announcement[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly alertClass = announcementAlertClass;
  protected readonly icon = announcementIcon;

  protected readonly severities: AnnouncementSeverity[] = ['info', 'success', 'warning', 'danger'];

  protected readonly form = this.fb.nonNullable.group({
    message: ['', [Validators.required, Validators.maxLength(500)]],
    severity: ['info' as AnnouncementSeverity, [Validators.required]],
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.listAll().subscribe({
      next: (rows) => {
        this.items.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load announcements. Please try again.');
        this.loading.set(false);
      },
    });
  }

  post(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const { message, severity } = this.form.getRawValue();
    this.service.create({ message: message.trim(), severity }).subscribe({
      next: (created) => {
        this.items.update((list) => [created, ...list]);
        this.form.reset({ message: '', severity: 'info' });
        this.saving.set(false);
        this.toasts.success('Announcement posted');
      },
      error: () => {
        this.saving.set(false);
        this.toasts.error('Could not post the announcement.');
      },
    });
  }

  toggle(a: Announcement): void {
    this.service.setActive(a.id, !a.active).subscribe({
      next: (updated) => {
        this.items.update((list) => list.map((it) => (it.id === a.id ? updated : it)));
      },
      error: () => this.toasts.error('Could not update the announcement.'),
    });
  }

  async remove(a: Announcement): Promise<void> {
    const ok = await this.confirm.confirm({
      title: 'Delete announcement',
      message: 'This removes the announcement for everyone. Continue?',
      confirmLabel: 'Delete',
      icon: 'ti-trash',
      danger: true,
    });
    if (!ok) {
      return;
    }
    this.service.remove(a.id).subscribe({
      next: () => {
        this.items.update((list) => list.filter((it) => it.id !== a.id));
        this.toasts.success('Announcement deleted');
      },
      error: () => this.toasts.error('Could not delete the announcement.'),
    });
  }
}
