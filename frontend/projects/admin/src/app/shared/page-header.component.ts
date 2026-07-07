import { Component, Input, computed, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

/** A single breadcrumb entry. A missing {@link link} renders as plain text. */
export interface Breadcrumb {
  label: string;
  link?: string;
}

/**
 * Consistent page header used at the top of every routed admin page.
 *
 * <p>Renders a breadcrumb trail (always rooted at "Dashboard"), the page title,
 * an optional subtitle, and a right-aligned actions slot projected via
 * {@code <ng-content>}. On small screens the actions wrap below the title (see
 * the {@code .shifa-page-head} rules in the global stylesheet).
 *
 * <p>Usage:
 * <pre>
 *   &lt;admin-page-header title="Orders" [breadcrumbs]="[{ label: 'Orders' }]"&gt;
 *     &lt;button class="btn btn-primary"&gt;Add&lt;/button&gt;
 *   &lt;/admin-page-header&gt;
 * </pre>
 */
@Component({
  selector: 'admin-page-header',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="shifa-page-head">
      <div class="shifa-page-head__title">
        <nav class="shifa-breadcrumbs" aria-label="Breadcrumb">
          <ol>
            @for (crumb of trail(); track $index; let last = $last) {
              <li [class.is-current]="last">
                @if (crumb.link && !last) {
                  <a [routerLink]="crumb.link">{{ crumb.label }}</a>
                } @else {
                  <span [attr.aria-current]="last ? 'page' : null">{{ crumb.label }}</span>
                }
              </li>
            }
          </ol>
        </nav>
        <h1 class="page-title">{{ title }}</h1>
        @if (subtitle) {
          <p class="shifa-page-head__desc">{{ subtitle }}</p>
        }
      </div>
      <div class="shifa-page-head__actions">
        <ng-content />
      </div>
    </div>
  `,
})
export class PageHeaderComponent {
  /** The main page title (also the current/last breadcrumb when none is supplied). */
  @Input({ required: true })
  set title(value: string) {
    this._title.set(value);
  }
  get title(): string {
    return this._title();
  }
  private readonly _title = signal('');

  /** Optional short description shown under the title. */
  @Input() subtitle?: string;

  /**
   * Breadcrumb entries between the "Dashboard" root and the current page. When
   * omitted, the trail is just Dashboard → {@link title}.
   */
  @Input()
  set breadcrumbs(value: Breadcrumb[] | null | undefined) {
    this._breadcrumbs.set(value ?? []);
  }
  private readonly _breadcrumbs = signal<Breadcrumb[]>([]);

  /**
   * The full breadcrumb trail: a Dashboard root (unless the caller already
   * starts with it), the supplied crumbs, and a trailing crumb for the current
   * page derived from the title when the caller did not provide one.
   */
  protected readonly trail = computed<Breadcrumb[]>(() => {
    const supplied = this._breadcrumbs();
    const root: Breadcrumb = { label: 'Dashboard', link: '/dashboard' };
    const startsWithRoot = supplied[0]?.label === 'Dashboard';
    const middle = startsWithRoot ? supplied.slice(1) : supplied;
    const hasCurrent = middle.length > 0 && middle[middle.length - 1].label === this.title;
    const tail = hasCurrent ? [] : [{ label: this.title }];
    const all = [root, ...middle, ...tail];
    // Collapse adjacent duplicate labels (e.g. the Dashboard page itself),
    // keeping the later entry so the current page renders as plain text.
    return all.filter((crumb, i) => all[i + 1]?.label !== crumb.label);
  });
}
