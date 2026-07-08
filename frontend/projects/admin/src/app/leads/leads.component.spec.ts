import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService, Role, provideCore } from 'core';
import { LeadsComponent } from './leads.component';
import { LeadsService } from './leads.service';
import { LeadDetail, LeadSummary } from './leads.model';

/**
 * Mobile-first component checks for the Leads pipeline screen (Task 8.5, Req
 * 8.1, 8.2) at a 360px viewport. Verifies the pipeline cards + status filter
 * tabs render, the capture form opens with a required name + source, and the
 * lead-detail drawer exposes the Convert action for an active lead.
 */
const MOBILE_WIDTH = 360;

function setViewport(width: number): void {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true });
  window.dispatchEvent(new Event('resize'));
}

const LEADS: LeadSummary[] = [
  {
    id: 1,
    customerName: 'Asha Rao',
    customerMobile: '9876543210',
    leadSource: 'WHATSAPP',
    status: 'NEW',
    ownerUserId: 5,
    createdAt: '2024-06-01T10:00:00',
  },
  {
    id: 2,
    customerName: 'Vikram S',
    leadSource: 'INSTAGRAM',
    status: 'QUOTED',
    ownerUserId: 5,
    createdAt: '2024-06-02T10:00:00',
  },
];

const DETAIL: LeadDetail = {
  id: 1,
  customerName: 'Asha Rao',
  customerMobile: '9876543210',
  leadSource: 'WHATSAPP',
  status: 'NEW',
  ownerUserId: 5,
  statusHistory: [{ fromStatus: null, toStatus: 'NEW', actor: 'sales1', changedAt: '2024-06-01T10:00:00' }],
};

class LeadsServiceStub {
  list() {
    return of(LEADS);
  }
  pipeline() {
    return of({ NEW: 1, CONTACTED: 0, QUOTED: 1, WON: 0, LOST: 0 });
  }
  detail() {
    return of(DETAIL);
  }
}

const AUTH_STUB = { hasAnyRole: (..._roles: Role[]) => true } as unknown as AuthService;

async function setup(): Promise<ComponentFixture<LeadsComponent>> {
  await TestBed.configureTestingModule({
    imports: [LeadsComponent],
    providers: [
      provideRouter([]),
      provideCore('http://localhost:8080'),
      { provide: LeadsService, useValue: new LeadsServiceStub() },
      { provide: AuthService, useValue: AUTH_STUB },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(LeadsComponent);
  fixture.detectChanges();
  return fixture;
}

describe('LeadsComponent (mobile-first pipeline)', () => {
  beforeEach(() => setViewport(MOBILE_WIDTH));

  it('renders tappable lead cards with status pills + stage filter tabs', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.shifa-lc').length).toBe(2);
    expect(el.querySelectorAll('.shifa-lc__pill').length).toBe(2);
    // All + 5 stages = 6 tabs, each carrying a count badge.
    expect(el.querySelectorAll('.shifa-status-tab').length).toBe(6);
    expect(el.querySelectorAll('.shifa-tab-count').length).toBe(6);
  });

  it('opens the capture form with a required name + source (Req 8.2)', async () => {
    const fixture = await setup();
    const component = fixture.componentInstance;
    component.openCapture();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[formcontrolname="customerName"]')).not.toBeNull();
    expect(el.querySelector('[formcontrolname="leadSource"]')).not.toBeNull();
    expect(component['leadForm'].valid).toBe(false);
  });

  it('exposes the Convert action in the detail drawer for an active lead', async () => {
    const fixture = await setup();
    const component = fixture.componentInstance;
    component.openDetail(LEADS[0]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.shifa-ld__convert')).not.toBeNull();
    expect(el.querySelector('.shifa-ld__primary')).not.toBeNull();
  });
});
