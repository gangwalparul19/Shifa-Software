import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService, Role, provideCore } from 'core';
import { DashboardComponent } from './dashboard.component';
import { DashboardService } from './dashboard.service';
import { RoleDashboardSummary } from './dashboard.model';

/**
 * Mobile-first component tests for the role-aware dashboard (Task 10.6, Req
 * 17.1–17.3, 3.1–3.5). At a 360px viewport a non-admin role should render its
 * summary as stacked count cards (single column, responsive column classes)
 * with comfortable touch targets, and never the admin-only period control.
 */
const MOBILE_WIDTH = 360;

function setViewport(width: number): void {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true });
  window.dispatchEvent(new Event('resize'));
}

function salespersonSummary(): RoleDashboardSummary {
  return {
    role: 'SALESPERSON',
    salesperson: {
      ordersByStatus: { PENDING_ADMIN_APPROVAL: 2, PACKED: 1, DELIVERED: 3 },
      awaitingApproval: 2,
      leadPipeline: { NEW: 3, CONTACTED: 1, QUOTED: 2 },
      dueFollowUps: 4,
    },
    admin: null,
    packing: null,
    accountant: null,
  };
}

function packingSummary(): RoleDashboardSummary {
  return {
    role: 'PACKING_USER',
    salesperson: null,
    admin: null,
    packing: {
      approvedAwaitingPacking: 4,
      packedToday: 5,
      awaitingHandover: 2,
      awaitingDispatch: 1,
    },
    accountant: null,
  };
}

class DashboardServiceStub {
  summary: RoleDashboardSummary = salespersonSummary();
  roleSummary() {
    return of(this.summary);
  }
  metrics() {
    return of(null as never);
  }
  liveStats() {
    return of(null as never);
  }
  activity() {
    return of(null as never);
  }
}

function authStub(role: Role) {
  return { session: signal({ userId: 1, username: 'user', role }) };
}

async function setup(role: Role, service: DashboardServiceStub): Promise<ComponentFixture<DashboardComponent>> {
  await TestBed.configureTestingModule({
    imports: [DashboardComponent],
    providers: [
      provideRouter([]),
      provideCore('http://localhost:8080'),
      { provide: DashboardService, useValue: service },
      { provide: AuthService, useValue: authStub(role) },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(DashboardComponent);
  fixture.detectChanges();
  return fixture;
}

describe('DashboardComponent (mobile-first, role-aware)', () => {
  beforeEach(() => setViewport(MOBILE_WIDTH));

  it('renders a salesperson card set (no admin period control) at 360px', async () => {
    const service = new DashboardServiceStub();
    service.summary = salespersonSummary();
    const fixture = await setup(Role.SALESPERSON, service);
    const el = fixture.nativeElement as HTMLElement;

    // Admin-only period tabs must not render for a salesperson.
    expect(el.querySelector('.shifa-period')).toBeNull();

    // The role summary renders "awaiting approval" + by-status cards.
    expect(el.textContent).toContain('Awaiting approval');
    expect(el.textContent).toContain('My orders');

    // Cards use single-column-friendly responsive grid columns (Req 17.1, 17.3).
    const cards = el.querySelectorAll('.row-cards .stat-accent');
    expect(cards.length).toBeGreaterThan(0);
    cards.forEach((card) => {
      const col = card.closest('[class*="col-"]');
      expect(col).not.toBeNull();
    });
  });

  it('renders packer queues with ≥44px touch action at 360px', async () => {
    const service = new DashboardServiceStub();
    service.summary = packingSummary();
    const fixture = await setup(Role.PACKING_USER, service);
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('Packing queues');
    expect(el.textContent).toContain('Awaiting handover');
    expect(el.textContent).toContain('Awaiting dispatch');

    // The primary call-to-action carries the shared 44px touch-target class.
    const cta = el.querySelector('a.shifa-touch');
    expect(cta).not.toBeNull();
  });

  it('shows the admin period control for an admin', async () => {
    const service = new DashboardServiceStub();
    service.summary = { role: 'ADMIN', salesperson: null, admin: null, packing: null, accountant: null };
    const fixture = await setup(Role.ADMIN, service);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.shifa-period')).not.toBeNull();
  });
});
