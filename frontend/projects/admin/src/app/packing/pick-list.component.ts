import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { PackingService } from './packing.service';
import { PickList } from './packing.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';

/**
 * Daily pick-list / packing manifest (enhancement).
 *
 * <p>One printable sheet listing every product needed across all orders
 * currently awaiting packing ({@code Label_Generated}), aggregated so the
 * packer picks stock once per product instead of walking the stock room per
 * order. Sorted highest-quantity first (pick the bulk items first).
 */
@Component({
  selector: 'admin-pick-list',
  imports: [DatePipe, PageHeaderComponent, StatePanelComponent],
  templateUrl: './pick-list.component.html',
  styleUrl: './pick-list.component.css',
})
export class PickListComponent implements OnInit {
  private readonly service = inject(PackingService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly pickList = signal<PickList | null>(null);
  protected readonly generatedAt = signal<Date | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.pickList().subscribe({
      next: (res) => {
        this.pickList.set(res);
        this.generatedAt.set(new Date());
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load the pick-list. Please try again.');
        this.loading.set(false);
      },
    });
  }

  print(): void {
    if (typeof window !== 'undefined') {
      window.print();
    }
  }
}
