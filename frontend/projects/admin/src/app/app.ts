import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ConfirmDialogComponent } from './shared/confirm-dialog.component';

/**
 * Admin application root. Renders the router outlet plus the single app-wide
 * confirmation dialog; the authenticated chrome (sidebar nav + top bar) lives in
 * {@code AdminShellComponent}, which wraps the protected feature routes, while
 * {@code login} / {@code forbidden} render standalone.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ConfirmDialogComponent],
  template: '<router-outlet /><admin-confirm-dialog />',
})
export class App {}
