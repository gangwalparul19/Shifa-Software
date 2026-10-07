import { Component } from '@angular/core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { DeliveryPartnerOverviewComponent } from './delivery-partner-overview.component';

/**
 * Standalone ADMIN page for the "Orders by delivery partner" overview, reached
 * from the sidebar. It wraps the self-contained {@link DeliveryPartnerOverviewComponent}
 * (which loads its own data, period presets, and drill-downs) in the standard
 * page header so it reads like a first-class page rather than a dashboard widget.
 */
@Component({
  selector: 'admin-delivery-partners-page',
  standalone: true,
  imports: [PageHeaderComponent, DeliveryPartnerOverviewComponent],
  template: `
    <admin-page-header
      title="Delivery Partners"
      subtitle="Orders split across QuikShipX, In-house (Ishika Enterprise), and POS — in transit, delivered, cancelled, and COD still to collect."
      [breadcrumbs]="[{ label: 'Delivery Partners' }]"
    />
    <admin-delivery-partner-overview />
  `,
})
export class DeliveryPartnersPageComponent {}
