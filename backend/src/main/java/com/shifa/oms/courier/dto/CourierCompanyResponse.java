package com.shifa.oms.courier.dto;

import com.shifa.oms.courier.CourierCompany;

/**
 * A selectable delivery-partner option for the "Assign courier" dropdown
 * (delivery-partner dropdown enhancement). Flat projection of
 * {@link CourierCompany} — just the id + display name.
 *
 * @param id   the courier company's id
 * @param name the courier company's display name (e.g. "QuikShipX", "Blue Dart")
 */
public record CourierCompanyResponse(Long id, String name) {

    public static CourierCompanyResponse from(CourierCompany company) {
        return new CourierCompanyResponse(company.getId(), company.getName());
    }
}
