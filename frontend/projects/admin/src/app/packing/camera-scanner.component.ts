import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  output,
  signal,
} from '@angular/core';
import { BrowserMultiFormatReader, IScannerControls } from '@zxing/browser';
import { BarcodeFormat, DecodeHintType } from '@zxing/library';

/**
 * Phone-camera barcode scanner overlay for packing (FEATURE-ROADMAP §8.2).
 *
 * <p>Opens the rear camera and decodes the Code 128 barcode printed on the
 * internal label (the order code) using ZXing, which works across Chrome, Edge,
 * Firefox and Safari (desktop + mobile) — unlike the native {@code BarcodeDetector},
 * which many browsers (Firefox, iOS Safari, older Chrome) don't implement. On a
 * successful read it emits {@link scanned} with the decoded value and the caller
 * feeds it into the existing scan flow — so no dedicated hardware scanner is needed.
 *
 * <p>Camera access requires a <strong>secure context (HTTPS or localhost)</strong>;
 * on a plain http:// origin the browser blocks the camera, so we detect that and
 * show a clear message telling the packer to use the https:// URL (or type the code).
 */
@Component({
  selector: 'admin-camera-scanner',
  standalone: true,
  template: `
    <div class="cam-overlay" (click)="close()"></div>
    <div class="cam-modal" role="dialog" aria-label="Scan barcode with camera">
      <header class="cam-head">
        <span><i class="ti ti-camera me-1"></i> Scan barcode</span>
        <button type="button" class="cam-close" (click)="close()" aria-label="Close">
          <i class="ti ti-x"></i>
        </button>
      </header>

      @if (error()) {
        <div class="cam-error">
          <i class="ti ti-camera-off"></i>
          <p>{{ error() }}</p>
          <button type="button" class="btn btn-outline-light btn-sm" (click)="close()">Close</button>
        </div>
      } @else {
        <div class="cam-stage">
          <!-- muted + playsinline are required for autoplay on mobile Safari/Chrome -->
          <video #video class="cam-video" muted playsinline></video>
          <div class="cam-reticle"></div>
        </div>
        <p class="cam-hint">Point the camera at the barcode on the label.</p>
      }
    </div>
  `,
  styles: [
    `
      :host {
        position: fixed;
        inset: 0;
        z-index: 1080;
      }
      .cam-overlay {
        position: absolute;
        inset: 0;
        background: rgba(0, 0, 0, 0.6);
      }
      .cam-modal {
        position: absolute;
        left: 50%;
        top: 50%;
        transform: translate(-50%, -50%);
        width: min(94vw, 460px);
        background: #0d1b14;
        color: #fff;
        border-radius: 16px;
        overflow: hidden;
        box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
      }
      .cam-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 0.75rem 1rem;
        font-weight: 700;
        background: linear-gradient(135deg, #1f5d3f, #164632);
      }
      .cam-close {
        border: none;
        background: rgba(255, 255, 255, 0.15);
        color: #fff;
        width: 32px;
        height: 32px;
        border-radius: 50%;
        cursor: pointer;
      }
      .cam-stage {
        position: relative;
        aspect-ratio: 4 / 3;
        background: #000;
      }
      .cam-video {
        width: 100%;
        height: 100%;
        object-fit: cover;
        display: block;
      }
      .cam-reticle {
        position: absolute;
        left: 10%;
        right: 10%;
        top: 38%;
        bottom: 38%;
        border: 2px solid rgba(143, 211, 166, 0.9);
        border-radius: 10px;
        box-shadow: 0 0 0 100vmax rgba(0, 0, 0, 0.25);
      }
      .cam-hint {
        margin: 0;
        padding: 0.75rem 1rem;
        font-size: 0.85rem;
        text-align: center;
        color: rgba(255, 255, 255, 0.85);
      }
      .cam-error {
        padding: 1.5rem 1.25rem;
        text-align: center;
      }
      .cam-error .ti {
        font-size: 2rem;
        color: #f0a;
      }
      .cam-error p {
        margin: 0.5rem 0 1rem;
        font-size: 0.9rem;
      }
    `,
  ],
})
export class CameraScannerComponent implements AfterViewInit, OnDestroy {
  /** Emits the decoded barcode value on a successful scan. */
  readonly scanned = output<string>();
  /** Emits when the overlay is dismissed without a scan. */
  readonly closed = output<void>();

  @ViewChild('video') private video?: ElementRef<HTMLVideoElement>;

  protected readonly error = signal<string | null>(null);

  private controls: IScannerControls | null = null;
  private done = false;

  async ngAfterViewInit(): Promise<void> {
    // Camera access is only granted in a secure context (HTTPS or localhost).
    // On a plain http:// origin `navigator.mediaDevices` is undefined, so no
    // library can open the camera — surface a precise, actionable message.
    const insecure =
      typeof window !== 'undefined' && window.isSecureContext === false;
    if (!navigator.mediaDevices?.getUserMedia) {
      this.error.set(
        insecure
          ? 'Camera scanning needs a secure (HTTPS) connection. Open the app using its https:// address, or type the order code instead.'
          : 'Camera access is not available on this device/browser. Please type the order code instead.',
      );
      return;
    }
    const el = this.video?.nativeElement;
    if (!el) {
      this.error.set('Could not initialise the camera view. Please type the order code instead.');
      return;
    }
    try {
      const hints = new Map<DecodeHintType, unknown>();
      hints.set(DecodeHintType.POSSIBLE_FORMATS, [
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.EAN_13,
        BarcodeFormat.QR_CODE,
      ]);
      const reader = new BrowserMultiFormatReader(hints, {
        delayBetweenScanAttempts: 250,
      });
      // Prefer the rear camera on phones; falls back to the default device.
      this.controls = await reader.decodeFromConstraints(
        { video: { facingMode: { ideal: 'environment' } }, audio: false },
        el,
        (result) => {
          if (result && !this.done) {
            const value = result.getText()?.trim();
            if (value) {
              this.done = true;
              this.stop();
              this.scanned.emit(value);
            }
          }
        },
      );
    } catch (e) {
      const name = (e as { name?: string })?.name;
      this.error.set(
        name === 'NotAllowedError'
          ? 'Camera permission was blocked. Allow camera access in your browser and try again, or type the order code.'
          : 'Could not open the camera. Check permissions, or type the order code instead.',
      );
      this.stop();
    }
  }

  ngOnDestroy(): void {
    this.stop();
  }

  close(): void {
    this.stop();
    this.closed.emit();
  }

  private stop(): void {
    if (this.controls) {
      try {
        this.controls.stop();
      } catch {
        /* already stopped */
      }
      this.controls = null;
    }
  }
}
