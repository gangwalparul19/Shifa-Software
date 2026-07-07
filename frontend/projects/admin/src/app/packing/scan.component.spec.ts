import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideCore } from 'core';
import { ScanComponent } from './scan.component';
import { PackingService } from './packing.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { RoleDashboardSummary } from '../dashboard/dashboard.model';

/**
 * Mobile-first component tests for the Packing screen (Task 10.6, Req 17.1–17.3,
 * 9.4, 10.1). Verifies the awaiting-handover / awaiting-dispatch queue context
 * renders as stacked cards and the scan area carries comfortable touch targets.
 */
const MOBILE_WIDTH = 360;

function setViewport(width: number): void {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true });
  window.dispatchEvent(new Event('resize'));
}

function packingSummary(): RoleDashboardSummary {
  return {
    role: 'PACKING_USER',
    salesperson: null,
    admin: null,
    packing: {
      approvedAwaitingPacking: 3,
      packedToday: 4,
      awaitingHandover: 2,
      awaitingDispatch: 1,
    },
    accountant: null,
  };
}

class PackingServiceStub {
  scan() {
    return of({ message: 'ok', order: null as never });
  }
  handover() {
    return of({} as never);
  }
  dispatch() {
    return of({} as never);
  }
}

class DashboardServiceStub {
  roleSummary() {
    return of(packingSummary());
  }
}

async function setup(): Promise<ComponentFixture<ScanComponent>> {
  await TestBed.configureTestingModule({
    imports: [ScanComponent],
    providers: [
      provideCore('http://localhost:8080'),
      { provide: PackingService, useValue: new PackingServiceStub() },
      { provide: DashboardService, useValue: new DashboardServiceStub() },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(ScanComponent);
  fixture.detectChanges();
  return fixture;
}

describe('ScanComponent (packing, mobile-first)', () => {
  beforeEach(() => setViewport(MOBILE_WIDTH));

  it('renders the awaiting-handover / awaiting-dispatch queue cards', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Awaiting handover');
    expect(el.textContent).toContain('Awaiting dispatch');
    // Rendered as responsive stat cards (single column at 360px, Req 17.3).
    const cards = el.querySelectorAll('.row-cards .stat-accent');
    expect(cards.length).toBe(4);
    cards.forEach((card) => expect(card.closest('[class*="col-"]')).not.toBeNull());
  });

  it('gives the scan controls a ≥44px touch container', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.packing-scan.shifa-touch')).not.toBeNull();
    // The scan submit button is a large touch target.
    expect(el.querySelector('.packing-scan .btn-lg')).not.toBeNull();
  });
});
