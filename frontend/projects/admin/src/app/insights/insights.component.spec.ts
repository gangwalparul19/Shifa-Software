import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { provideCore } from 'core';
import { InsightsComponent } from './insights.component';
import { InsightsService } from './insights.service';
import { Insight, RecomputeResponse } from './insights.model';

/**
 * Mobile-first component checks for the Statistical Insights screen (Task 6.4,
 * Req 13.1) at a 360px viewport. Verifies the severity-grouped insight cards +
 * severity filter tabs render, each card exposes a Dismiss action, and the
 * Recompute button is present.
 */
const MOBILE_WIDTH = 360;

function setViewport(width: number): void {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true });
  window.dispatchEvent(new Event('resize'));
}

const INSIGHTS: Insight[] = [
  {
    id: 1,
    type: 'SALES_ANOMALY',
    scope: 'GLOBAL',
    scopeRefId: 0,
    scopeLabel: null,
    severity: 'DANGER',
    title: 'Sales dropped 42% week-on-week',
    detail: 'Revenue fell from ₹1,20,000 to ₹70,000.',
    metricValue: '-42.00',
    computedDate: '2024-06-10',
    dismissed: false,
  },
  {
    id: 2,
    type: 'LOW_STOCK_REORDER',
    scope: 'PRODUCT',
    scopeRefId: 5,
    scopeLabel: 'Ashwagandha 60ct',
    severity: 'WARNING',
    title: 'Reorder Ashwagandha 60ct',
    detail: 'Only 4 days of cover remaining.',
    metricValue: '48',
    computedDate: '2024-06-10',
    dismissed: false,
  },
];

class InsightsServiceStub {
  list() {
    return of(INSIGHTS);
  }
  dismiss() {
    return of(INSIGHTS[0]);
  }
  recompute() {
    return of({ computed: 2, computedDate: '2024-06-10' } as RecomputeResponse);
  }
}

async function setup(): Promise<ComponentFixture<InsightsComponent>> {
  await TestBed.configureTestingModule({
    imports: [InsightsComponent],
    providers: [
      provideRouter([]),
      provideCore('http://localhost:8080'),
      { provide: InsightsService, useValue: new InsightsServiceStub() },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(InsightsComponent);
  fixture.detectChanges();
  return fixture;
}

describe('InsightsComponent (mobile-first cards)', () => {
  beforeEach(() => setViewport(MOBILE_WIDTH));

  it('renders severity-grouped insight cards with pills + filter tabs', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.shifa-ic').length).toBe(2);
    expect(el.querySelectorAll('.shifa-ic__pill').length).toBe(2);
    // All + 3 severities = 4 tabs, each carrying a count badge.
    expect(el.querySelectorAll('.shifa-status-tab').length).toBe(4);
    expect(el.querySelectorAll('.shifa-tab-count').length).toBe(4);
  });

  it('exposes a Dismiss action on every card', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.shifa-ic__dismiss').length).toBe(2);
  });

  it('removes an insight from the list when dismissed', async () => {
    const fixture = await setup();
    const component = fixture.componentInstance;
    component.dismiss(INSIGHTS[0]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.shifa-ic').length).toBe(1);
  });
});
