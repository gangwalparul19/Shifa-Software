import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from 'core';

/**
 * Customer account hub, reached through the customer auth guard (Req 5.2). Shows
 * a welcome and quick links into the account sections — My Orders, Addresses,
 * Profile, and the wishlist — plus sign-out. Each section is its own route.
 */
@Component({
  selector: 'sf-account',
  imports: [RouterLink],
  templateUrl: './account.component.html',
  styleUrl: './account.css',
})
export class AccountComponent {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  signOut(): void {
    this.auth.logout();
    void this.router.navigateByUrl('/');
  }
}
