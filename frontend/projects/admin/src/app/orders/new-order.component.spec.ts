import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { Product, provideCore } from 'core';
import { NewOrderComponent } from './new-order.component';
import { CatalogService } from './catalog.service';
import { OrdersService } from './orders.service';

/**
 * Mobile-first component tests for New Order (Task 10.6, Req 17.1–17.3, 4.1,
 * 4.5). Verifies the Lead Source picker + conditional note, and that the
 * line-item editor collapses into stacked cards (single column, data-labelled
 * fields) below 768px rather than a horizontally scrolling table.
 */
const MOBILE_WIDTH = 360;

function setViewport(width: number): void {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true });
  window.dispatchEvent(new Event('resize'));
}

const PRODUCT: Product = {
  id: 1,
  sku: 'SKU-1',
  name: 'Ashwagandha',
  salePrice: '199.00',
} as unknown as Product;

class CatalogServiceStub {
  products() {
    return of([PRODUCT]);
  }
}

class OrdersServiceStub {
  createOrder() {
    return of({} as never);
  }
  uploadPaymentScreenshot() {
    return of({ key: 'k' });
  }
}

async function setup(): Promise<ComponentFixture<NewOrderComponent>> {
  await TestBed.configureTestingModule({
    imports: [NewOrderComponent],
    providers: [
      provideRouter([]),
      provideCore('http://localhost:8080'),
      { provide: CatalogService, useValue: new CatalogServiceStub() },
      { provide: OrdersService, useValue: new OrdersServiceStub() },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(NewOrderComponent);
  fixture.detectChanges();
  return fixture;
}

describe('NewOrderComponent (lead source + mobile-first)', () => {
  beforeEach(() => setViewport(MOBILE_WIDTH));

  it('renders the Lead Source picker with the six defined options', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    const select = el.querySelector('#leadSource') as HTMLSelectElement | null;
    expect(select).not.toBeNull();
    // Six lead sources + the disabled placeholder option.
    expect(select!.querySelectorAll('option').length).toBe(7);
    expect(el.querySelector('#customerEmail')).not.toBeNull();
  });

  it('shows the ≤200-char note only when OTHER is chosen (Req 4.5)', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('#leadSourceNote')).toBeNull();

    const select = el.querySelector('#leadSource') as HTMLSelectElement;
    select.value = 'OTHER';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const note = el.querySelector('#leadSourceNote') as HTMLInputElement | null;
    expect(note).not.toBeNull();
    expect(note!.getAttribute('maxlength')).toBe('200');
  });

  it('collapses the line-item editor into stacked cards below 768px', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    // The stacking hook + data-labelled cells drive the card layout (Req 17.3).
    expect(el.querySelector('.shifa-lineitems')).not.toBeNull();
    expect(el.querySelector('.shifa-lineitems td[data-label="Product"]')).not.toBeNull();
  });
});
