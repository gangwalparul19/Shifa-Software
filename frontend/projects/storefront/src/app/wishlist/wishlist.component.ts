import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AuthService } from 'core';
import { catchError, of } from 'rxjs';
import { WishlistService } from '../shared/wishlist.service';
import { WhatsAppService } from '../shared/whatsapp.service';
import { ToastService } from '../shared/toast.service';
import { AccountApi } from '../account/account-api.service';
import { CartItem } from '../shared/cart-item.model';
import { discountPercent, formatInr } from '../shared/money';

/**
 * Wishlist page (route 'wishlist', Req 2.4, 2.5, 2.8): a grid of saved products
 * with move-to-cart and remove controls, plus an empty state. Set semantics
 * (no duplicates) are enforced by the {@link WishlistService}.
 *
 * <p>Signed-in customers can also share the wishlist: "Share wishlist" creates a
 * public, read-only link (copied to the clipboard), which can then be sent on
 * WhatsApp or revoked. The link points at the public {@code /wishlist/shared/:token}
 * view.
 */
@Component({
  selector: 'sf-wishlist',
  imports: [RouterLink, TranslatePipe],
  templateUrl: './wishlist.component.html',
  styleUrl: './wishlist.component.css',
})
export class WishlistComponent {
  protected readonly wishlist = inject(WishlistService);
  private readonly auth = inject(AuthService);
  private readonly accountApi = inject(AccountApi);
  private readonly whatsapp = inject(WhatsAppService);
  private readonly translate = inject(TranslateService);
  private readonly toasts = inject(ToastService);

  /** Share controls appear only for a signed-in customer. */
  protected readonly isLoggedIn = computed(() => this.auth.isAuthenticated());
  /** Absolute share URL once created, else null (drives WhatsApp/revoke buttons). */
  protected readonly shareUrl = signal<string | null>(null);
  protected readonly sharing = signal(false);

  price(item: CartItem): string {
    return formatInr(item.salePrice);
  }

  mrp(item: CartItem): string {
    return formatInr(item.mrp);
  }

  discount(item: CartItem): number {
    return discountPercent(item.mrp, item.salePrice);
  }

  moveToCart(productId: number): void {
    this.wishlist.moveToCart(productId);
  }

  remove(productId: number): void {
    this.wishlist.remove(productId);
  }

  /**
   * Creates (or reuses) the public share link, resolves it to an absolute URL,
   * copies it to the clipboard, and shows a confirmation.
   */
  share(): void {
    if (this.sharing()) {
      return;
    }
    this.sharing.set(true);
    this.accountApi
      .createWishlistShare()
      .pipe(catchError(() => of(null)))
      .subscribe((share) => {
        this.sharing.set(false);
        if (!share) {
          this.toasts.error(this.t('wishlist.shareError'));
          return;
        }
        const url = `${window.location.origin}${share.shareUrl}`;
        this.shareUrl.set(url);
        this.copyToClipboard(url);
      });
  }

  /** Opens WhatsApp prefilled with the share link so it can be sent to a contact. */
  shareOnWhatsApp(): void {
    const url = this.shareUrl();
    if (!url) {
      return;
    }
    const message = `${this.t('wishlist.shareWhatsAppIntro')}\n${url}`;
    window.open(this.whatsapp.shareLink(message), '_blank');
  }

  /** Revokes the public link so the token stops resolving. */
  revokeShare(): void {
    this.accountApi
      .revokeWishlistShare()
      .pipe(catchError(() => of(null)))
      .subscribe(() => {
        this.shareUrl.set(null);
        this.toasts.success(this.t('wishlist.shareRevoked'));
      });
  }

  private copyToClipboard(url: string): void {
    const done = () => this.toasts.success(this.t('wishlist.shareCopied'));
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(url).then(done, done);
    } else {
      // Clipboard API unavailable (older/insecure context): still confirm the link.
      done();
    }
  }

  private t(key: string): string {
    return this.translate.instant(key) as string;
  }
}
