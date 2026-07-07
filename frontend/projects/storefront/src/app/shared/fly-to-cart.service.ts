import { Injectable } from '@angular/core';

/** Id of the header cart control the ghost image flies toward. */
const CART_TARGET_ID = 'sf-cart-target';

/**
 * Lightweight "fly to cart" micro-interaction (Phase 1.3 UI/UX).
 *
 * <p>When a product is added from a card or the detail page, a cloned "ghost"
 * of the product image is animated from the source element toward the header
 * cart icon, then fades out. It is purely cosmetic, CSS/Web-Animations driven
 * (no library), and a strict no-op when the user prefers reduced motion, when
 * the target/source is missing, or outside the browser. Failures are swallowed
 * so a missing animation never breaks the add-to-cart flow.
 */
@Injectable({ providedIn: 'root' })
export class FlyToCartService {
  /**
   * Animates a ghost of {@code source} (an image or its container) toward the
   * header cart. {@code imageUrl} overrides the ghost content when the source
   * isn't itself an image.
   */
  fly(source: Element | null | undefined, imageUrl?: string): void {
    if (!source || !this.animationsAllowed()) {
      return;
    }
    const target = document.getElementById(CART_TARGET_ID);
    if (!target) {
      return;
    }

    const from = source.getBoundingClientRect();
    const to = target.getBoundingClientRect();
    if (from.width === 0 || to.width === 0) {
      return;
    }

    const ghost = this.buildGhost(source, imageUrl, from);
    document.body.appendChild(ghost);

    const dx = to.left + to.width / 2 - (from.left + from.width / 2);
    const dy = to.top + to.height / 2 - (from.top + from.height / 2);

    const animation = ghost.animate(
      [
        { transform: 'translate(0, 0) scale(1)', opacity: 0.9 },
        {
          transform: `translate(${dx * 0.5}px, ${dy * 0.5 - 40}px) scale(0.6)`,
          opacity: 0.8,
          offset: 0.6,
        },
        { transform: `translate(${dx}px, ${dy}px) scale(0.15)`, opacity: 0.2 },
      ],
      { duration: 650, easing: 'cubic-bezier(0.4, 0, 0.2, 1)' },
    );

    const cleanup = () => ghost.remove();
    animation.addEventListener('finish', cleanup);
    animation.addEventListener('cancel', cleanup);

    // Give the cart badge a little pulse on arrival.
    setTimeout(() => target.classList.add('sf-cart-bump'), 520);
    setTimeout(() => target.classList.remove('sf-cart-bump'), 900);
  }

  /** Builds an absolutely-positioned clone of the source image for the flight. */
  private buildGhost(source: Element, imageUrl: string | undefined, from: DOMRect): HTMLElement {
    const ghost = document.createElement('div');
    const url = imageUrl ?? this.imageSrc(source);
    ghost.style.cssText = [
      'position:fixed',
      `left:${from.left}px`,
      `top:${from.top}px`,
      `width:${from.width}px`,
      `height:${from.height}px`,
      'border-radius:14px',
      'overflow:hidden',
      'pointer-events:none',
      'z-index:300',
      'box-shadow:0 10px 30px rgba(31,93,63,0.25)',
      url ? `background:center/cover no-repeat url("${url}")` : 'background:#1f5d3f',
    ].join(';');
    return ghost;
  }

  /** Resolves an image URL from the source element (or a descendant <img>). */
  private imageSrc(source: Element): string | null {
    if (source instanceof HTMLImageElement) {
      return source.currentSrc || source.src;
    }
    const img = source.querySelector('img');
    return img ? img.currentSrc || img.src : null;
  }

  /** True only in a browser that honours motion (respects reduced-motion). */
  private animationsAllowed(): boolean {
    if (typeof document === 'undefined' || typeof window === 'undefined') {
      return false;
    }
    if (typeof Element.prototype.animate !== 'function') {
      return false;
    }
    return !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  }
}
