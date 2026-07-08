import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Authorization-error view shown when an authenticated staff user navigates to a
 * section their role does not permit (Req 5.3). The role guard redirects here
 * instead of silently blocking, so the user gets a clear message. Restyled on
 * the Tabler empty-state pattern; behaviour unchanged.
 */
@Component({
  selector: 'admin-forbidden',
  imports: [RouterLink],
  template: `
    <div class="shifa-auth">
      <div class="shifa-auth__card">
        <div class="card card-md">
          <div class="card-body text-center py-5" role="alert">
            <div class="shifa-forbidden-icon mx-auto mb-3">
              <i class="ti ti-lock-off"></i>
            </div>
            <h1 class="h2 mb-2">Access denied</h1>
            <p class="text-secondary mb-4">
              Your role does not have permission to view this page.
            </p>
            <a class="btn btn-primary" routerLink="/dashboard">
              <i class="ti ti-arrow-left me-2"></i> Back to dashboard
            </a>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .shifa-auth__card .card {
        border: 1px solid rgba(15, 51, 36, 0.08);
        border-radius: 16px;
        box-shadow: 0 12px 34px rgba(15, 51, 36, 0.14);
      }
      .shifa-forbidden-icon {
        display: grid;
        place-items: center;
        width: 4.5rem;
        height: 4.5rem;
        border-radius: 50%;
        font-size: 2.2rem;
        color: #b42318;
        background: #fdecea;
      }
      .shifa-auth .btn-primary {
        min-height: 48px;
        font-weight: 600;
        border-radius: 10px;
      }
    `,
  ],
})
export class ForbiddenComponent {}
