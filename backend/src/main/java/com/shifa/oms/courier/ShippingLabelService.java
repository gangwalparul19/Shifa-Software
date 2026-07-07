package com.shifa.oms.courier;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.label.BarcodeGenerator;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.domain.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds and renders courier shipping labels (Req 12.3, 12.5), reusing the label
 * module's {@link BarcodeGenerator} through {@link ShippingLabelRenderer}.
 *
 * <p>Content assembly is pure ({@link #buildContent}) so the COD-iff rule
 * (COD/Partially_Paid include the COD amount, prepaid does not) can be reasoned
 * about directly. {@link #bulkShippingLabelPdf(List)} produces one label block
 * per requested order that has an assigned AWB (Req 12.5), skipping orders with
 * no AWB.
 */
@Service
public class ShippingLabelService {

    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final CourierCompanyRepository courierCompanyRepository;
    private final com.shifa.oms.settings.CompanyLogoService companyLogoService;
    private final ShippingLabelRenderer renderer;

    /** Test-friendly constructor without the company-logo collaborator (no logo). */
    public ShippingLabelService(OrderRepository orderRepository,
                                CourierRecordRepository courierRecordRepository,
                                CourierCompanyRepository courierCompanyRepository) {
        this(orderRepository, courierRecordRepository, courierCompanyRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ShippingLabelService(OrderRepository orderRepository,
                                CourierRecordRepository courierRecordRepository,
                                CourierCompanyRepository courierCompanyRepository,
                                com.shifa.oms.settings.CompanyLogoService companyLogoService) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
        this.companyLogoService = companyLogoService;
        this.renderer = new ShippingLabelRenderer(new BarcodeGenerator());
    }

    /** The configured company logo bytes for rendering, or {@code null} when none/absent. */
    private byte[] logoPng() {
        return companyLogoService != null ? companyLogoService.currentLogoPng().orElse(null) : null;
    }

    /**
     * Assembles the shipping-label content for an order that already has an AWB.
     *
     * @param order        the order aggregate
     * @param awb          the assigned AWB
     * @param courierName  the courier company name
     * @param trackingUrl  the customer tracking link (may be {@code null})
     * @return the render-agnostic shipping-label content
     */
    public ShippingLabelContent buildContent(OrderEntity order, String awb,
                                             String courierName, String trackingUrl) {
        List<ShippingLabelContent.LabelLineItem> items = new ArrayList<>();
        for (OrderLineItem li : order.getLineItems()) {
            items.add(new ShippingLabelContent.LabelLineItem(li.getProductName(), li.getQuantity()));
        }
        boolean codApplicable = isCodApplicable(order.getPaymentStatus());
        return new ShippingLabelContent(
                order.getOrderCode(),
                awb,
                courierName,
                trackingUrl,
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getAddressLine(),
                order.getCity(),
                order.getState(),
                order.getPostalCode(),
                items,
                codApplicable,
                codApplicable ? order.getCodAmount() : null);
    }

    /** Renders the shipping-label content to PDF bytes. */
    public byte[] render(ShippingLabelContent content) {
        return renderer.render(content, logoPng());
    }

    /**
     * Produces a single combined PDF with one shipping-label block per requested
     * order that has an assigned AWB, in request order (Req 12.5). Rejects an
     * empty request and the case where no requested order has an AWB.
     *
     * @param orderIds the orders to print shipping labels for (non-empty)
     * @return the combined shipping-label PDF bytes
     */
    @Transactional(readOnly = true)
    public byte[] bulkShippingLabelPdf(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new ValidationException("At least one order id is required for bulk shipping labels.");
        }
        List<ShippingLabelContent> contents = new ArrayList<>();
        for (Long id : orderIds) {
            OrderEntity order = orderRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
            Optional<CourierRecord> record = courierRecordRepository.findByOrderId(id);
            if (record.isEmpty() || record.get().getAwb() == null || record.get().getAwb().isBlank()) {
                continue; // No AWB yet — skip; only orders with an AWB get a shipping label.
            }
            CourierRecord cr = record.get();
            String courierName = "Courier";
            String trackingUrl = null;
            if (cr.getCourierCompanyId() != null) {
                Optional<CourierCompany> company = courierCompanyRepository.findById(cr.getCourierCompanyId());
                if (company.isPresent()) {
                    courierName = company.get().getName();
                    trackingUrl = company.get().trackingUrl(cr.getAwb());
                }
            }
            contents.add(buildContent(order, cr.getAwb(), courierName, trackingUrl));
        }
        if (contents.isEmpty()) {
            throw new ValidationException("None of the requested orders has an assigned AWB.");
        }
        return renderer.render(contents, logoPng());
    }

    private boolean isCodApplicable(PaymentStatus status) {
        return status == PaymentStatus.COD || status == PaymentStatus.PARTIALLY_PAID;
    }
}
