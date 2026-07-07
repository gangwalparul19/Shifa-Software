import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SeoService } from '../shared/seo.service';

/** Static "About" page telling the Shifa brand story (nav target). */
@Component({
  selector: 'sf-about',
  imports: [RouterLink],
  template: `
    <section class="page-hero">
      <div class="container">
        <span class="eyebrow">Our Story</span>
        <h1>Ancient wisdom, crafted for today</h1>
        <p>
          Shifa Herbal Remedies was born from a simple belief — that nature holds
          the answers to everyday wellness.
        </p>
      </div>
    </section>

    <section class="section container about-body">
      <div class="about-grid">
        <img src="/products/shifa-11.jpg" alt="Herbal ingredients" class="about-img" />
        <div>
          <h2>Rooted in tradition</h2>
          <p>
            Every Shifa product is made from carefully sourced herbs, prepared using
            time-honoured Ayurvedic methods and small-batch care. We never use
            parabens, sulphates or artificial colours.
          </p>
          <p>
            From immunity tonics to hair and skin essentials, our range is designed
            to help you feel your best — the natural way.
          </p>
          <a class="btn btn-primary" routerLink="/shop">Explore the Range</a>
        </div>
      </div>
    </section>
  `,
  styles: [
    `
      .page-hero {
        background: var(--herbal-green-soft);
        padding: 56px 0;
        text-align: center;
      }
      .page-hero .eyebrow {
        display: inline-block;
        text-transform: uppercase;
        letter-spacing: 0.2em;
        font-size: 0.74rem;
        font-weight: 600;
        color: var(--gold);
        margin-bottom: 10px;
      }
      .page-hero h1 {
        font-size: clamp(2rem, 4vw, 3rem);
        margin-bottom: 10px;
      }
      .page-hero p {
        color: var(--muted);
        max-width: 560px;
        margin: 0 auto;
      }
      .about-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 40px;
        align-items: center;
      }
      .about-img {
        width: 100%;
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow);
        object-fit: cover;
        aspect-ratio: 4 / 3;
      }
      .about-body h2 {
        font-size: 1.8rem;
        margin-bottom: 14px;
      }
      .about-body p {
        color: var(--muted);
        margin-bottom: 16px;
        line-height: 1.8;
      }
      @media (max-width: 768px) {
        .about-grid {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class AboutComponent {
  private readonly seo = inject(SeoService);

  constructor() {
    this.seo.setPage({
      title: 'About Us',
      description:
        'Shifa Herbal Remedies crafts authentic Ayurvedic wellness products from carefully ' +
        'sourced herbs using time-honoured, small-batch methods — no parabens or artificial colours.',
    });
  }
}
