package com.shifa.oms.adminexception;

import com.shifa.oms.adminexception.dto.AdminExceptionResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only read model for the operational exception center. */
@RestController
@RequestMapping("/api/admin/exceptions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminExceptionController {

    private final AdminExceptionService service;

    public AdminExceptionController(AdminExceptionService service) {
        this.service = service;
    }

    @GetMapping
    public AdminExceptionResponse list() {
        return service.list();
    }
}
