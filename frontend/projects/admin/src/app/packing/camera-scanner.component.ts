import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  output,
  signal,
} from '@angular/core';

/** Minimal shape of the native BarcodeDetector API (not in TS DOM lib yet). */
interface DetectedBarcode {
  rawValue: string;
}
interface BarcodeDetectorLike {
  detect(source: CanvasImageSource): Promise<DetectedBarcode[]>;
}
interface BarcodeDetectorCtor {
  new (options?: { formats?: string[] }): BarcodeDetectorLike;
  getSupportedFormats?: () => Promise<string[]>;
}

/**
 * Phone-camera barcode scanner overlay for packing (FEATURE-ROADMAP §8.2).
 *
 * <p>Opens the rear camera and uses the browser's native {@code BarcodeDetector}
 * to read the Code 128 barcode printed on the internal label (the order code).
 * On a successful read it emits {@link scanned} with the decoded value and the
 * caller feeds it into the existing scan flow — so no dedicated hardware scanner
 * is needed. When the API or camera is unavailable it shows a clear message and
 * the packer falls back to typing the code.
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

  private stream: MediaStream | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private detector: BarcodeDetectorLike | null = null;
  private done = false;

  async ngAfterViewInit(): Promise<void> {
    const ctor = (window as unknown as { BarcodeDetector?: BarcodeDetectorCtor }).BarcodeDetector;
    if (!ctor) {
      this.error.set(
        'This device/browser cannot scan with the camera. Please type the order code instead. (Tip: use Chrome on Android.)',
      );
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      this.error.set('Camera access is not available on this device. Please type the order code.');
      return;
    }
    try {
      this.detector = new ctor({ formats: ['code_128', 'qr_code', 'ean_13', 'code_39'] });
      this.stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment' },
        audio: false,
      });
      const el = this.video?.nativeElement;
      if (el) {
        el.srcObject = this.stream;
        await el.play();
      }
      this.timer = setInterval(() => void this.tick(), 350);
    } catch {
      this.error.set('Could not open the camera. Check permissions, or type the order code instead.');
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

  private async tick(): Promise<void> {
    if (this.done || !this.detector || !this.video?.nativeElement) {
      return;
    }
    try {
      const results = await this.detector.detect(this.video.nativeElement);
      const value = results?.[0]?.rawValue?.trim();
      if (value) {
        this.done = true;
        this.stop();
        this.scanned.emit(value);
      }
    } catch {
      /* transient decode error — keep trying */
    }
  }

  private stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    if (this.stream) {
      this.stream.getTracks().forEach((t) => t.stop());
      this.stream = null;
    }
  }
}
