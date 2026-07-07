import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { AuthService, Category } from 'core';
import { CartService } from './shared/cart.service';
import { WishlistService } from './shared/wishlist.service';
import { PwaInstallService } from './shared/pwa-install.service';
import { CartDrawerService } from './shared/cart-drawer.service';
import { CatalogService } from './catalog/catalog.service';
import { LanguageSwitcherComponent } from './shared/language-switcher.component';
import { EngagementDockComponent } from './shared/engagement/engagement-dock.component';
import { ToastsComponent } from './shared/toasts.component';
import { PwaInstallBannerComponent } from './shared/pwa-install-banner.component';
import { CartDrawerComponent } from './cart/cart-drawer.component';
import { StorefrontConfigService } from './shared/storefront-config.service';
import { WhatsAppService } from './shared/whatsapp.service';
import { ThemeService } from './shared/theme.service';

/**
 * Storefront layout shell: a sticky brand header with primary nav, search,
 * and live wishlist/cart count badges; a mobile hamburger menu; the routed
 * page; and a rich footer. Header badges bind to the {@link CartService} and
 * {@link WishlistService} signals so they update instantly.
 */
@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    TranslatePipe,
    LanguageSwitcherComponent,
    EngagementDockComponent,
    ToastsComponent,
    PwaInstallBannerComponent,
    CartDrawerComponent,
  ],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly cart = inject(CartService);
  protected readonly wishlist = inject(WishlistService);
  protected readonly pwa = inject(PwaInstallService);
  protected readonly cartDrawer = inject(CartDrawerService);
  protected readonly auth = inject(AuthService);
  protected readonly theme = inject(ThemeService);
  private readonly router = inject(Router);
  private readonly catalog = inject(CatalogService);
  // Eagerly load the public storefront config (WhatsApp number, support email).
  private readonly storefrontConfig = inject(StorefrontConfigService);
  private readonly whatsapp = inject(WhatsAppService);

  protected readonly menuOpen = signal(false);
  protected readonly categoriesOpen = signal(false);
  protected readonly accountOpen = signal(false);
  protected readonly categories = signal<Category[]>([]);
  protected readonly year = new Date().getFullYear();

  constructor() {
    this.catalog.categories().subscribe({
      next: (items) => this.categories.set(items),
      error: () => this.categories.set([]),
    });
  }

  toggleCategories(): void {
    this.categoriesOpen.update((open) => !open);
  }

  closeCategories(): void {
    this.categoriesOpen.set(false);
  }

  /** Triggers the browser's PWA install prompt (Requirement 4.3). */
  installApp(): void {
    void this.pwa.promptInstall();
  }

  toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  closeMenu(): void {
    this.menuOpen.set(false);
    this.categoriesOpen.set(false);
    this.accountOpen.set(false);
  }

  toggleAccount(): void {
    this.accountOpen.update((open) => !open);
  }

  /** Signs the customer out and returns to the home page. */
  signOut(): void {
    this.auth.logout();
    this.closeMenu();
    void this.router.navigate(['/']);
  }

  /** Opens the slide-in cart drawer from the header cart button. */
  openCart(): void {
    this.closeMenu();
    this.cartDrawer.openDrawer();
  }

  /** WhatsApp deep link for the footer, prefilled with the current cart. */
  whatsappHref(): string {
    return this.whatsapp.cartLink(this.cart.items());
  }

  /** Runs a header search: navigates to the shop with the query param. */
  submitSearch(term: string): void {
    const q = term.trim();
    this.closeMenu();
    void this.router.navigate(['/shop'], { queryParams: q ? { q } : {} });
  }
}
