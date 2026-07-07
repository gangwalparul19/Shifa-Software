import { Component, inject, signal } from '@angular/core';
import { SeoService } from '../shared/seo.service';

/** Static "Contact" page with brand contact details and a simple form (nav target). */
@Component({
  selector: 'sf-contact',
  template: `
    <section class="page-hero">
      <div class="container">
        <span class="eyebrow">We'd love to hear from you</span>
        <h1>Get in touch</h1>
        <p>Questions about a product or your order? Our wellness team is here to help.</p>
      </div>
    </section>

    <section class="section container contact-body">
      <div class="contact-grid">
        <div class="contact-info">
          <h2>Reach us</h2>
          <p class="line">📍 Ahmedabad, Gujarat, India</p>
          <p class="line">✆ +91 9302590767</p>
          <p class="line">✉ care&#64;shifaherbal.in</p>
          <p class="line">🕐 Mon–Sat, 9am – 6pm IST</p>
        </div>

        <form class="contact-form" (submit)="$event.preventDefault(); sent.set(true)">
          @if (sent()) {
            <p class="ok">Thanks! We'll get back to you shortly. 🌿</p>
          }
          <div class="field">
            <label for="cname">Name</label>
            <input id="cname" type="text" required />
          </div>
          <div class="field">
            <label for="cemail">Email</label>
            <input id="cemail" type="email" required />
          </div>
          <div class="field">
            <label for="cmsg">Message</label>
            <textarea id="cmsg" rows="4" required></textarea>
          </div>
          <button type="submit" class="btn btn-primary btn-block">Send Message</button>
        </form>
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
      .contact-grid {
        display: grid;
        grid-template-columns: 1fr 1.2fr;
        gap: 40px;
      }
      .contact-info h2 {
        font-size: 1.6rem;
        margin-bottom: 16px;
      }
      .contact-info .line {
        margin: 10px 0;
        color: var(--charcoal);
      }
      .contact-form {
        background: var(--white);
        border: 1px solid var(--line);
        border-radius: var(--radius-lg);
        padding: 28px;
      }
      .field {
        margin-bottom: 16px;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .field label {
        font-size: 0.86rem;
        font-weight: 500;
      }
      .field input,
      .field textarea {
        border: 1px solid var(--line);
        border-radius: 12px;
        padding: 12px 14px;
        font-family: inherit;
        font-size: 0.95rem;
        outline: none;
        resize: vertical;
      }
      .field input:focus,
      .field textarea:focus {
        border-color: var(--herbal-green-2);
        box-shadow: 0 0 0 3px rgba(46, 125, 91, 0.12);
      }
      .ok {
        background: var(--herbal-green-soft);
        color: var(--herbal-green);
        border-radius: var(--radius);
        padding: 12px 16px;
        margin-bottom: 16px;
        font-size: 0.9rem;
      }
      @media (max-width: 768px) {
        .contact-grid {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class ContactComponent {
  private readonly seo = inject(SeoService);
  protected readonly sent = signal(false);

  constructor() {
    this.seo.setPage({
      title: 'Contact Us',
      description:
        'Get in touch with the Shifa Herbal Remedies wellness team for help with products or orders.',
    });
  }
}
