/** Default delay before a browser object URL is revoked. PDFs need time to load in a new tab. */
export const DEFAULT_BLOB_REVOKE_DELAY_MS = 60_000;

/**
 * Resolves a safe filename while keeping a deterministic fallback for APIs that
 * return a Blob without Content-Disposition metadata.
 */
export function resolveBlobFilename(filename: string | null | undefined, fallback: string): string {
  const candidate = String(filename ?? '').trim();
  const safe = candidate.replace(/[\\/:*?"<>|]/g, '-').replace(/^-+|-+$/g, '').trim();
  return safe || fallback;
}

/**
 * Reads the download filename from a `Content-Disposition` header value, or
 * `null` when there is none. Understands the RFC 5987 form (`filename*=UTF-8''…`,
 * which wins when both are present), a quoted `filename="…"`, and a bare token.
 */
export function filenameFromContentDisposition(header: string | null | undefined): string | null {
  if (!header) {
    return null;
  }
  const extended = /filename\*\s*=\s*[^']*'[^']*'([^;]+)/i.exec(header);
  if (extended) {
    try {
      const decoded = decodeURIComponent(extended[1].trim());
      if (decoded) {
        return decoded;
      }
    } catch {
      // Malformed percent-encoding: fall through to the plain filename.
    }
  }
  const quoted = /filename\s*=\s*"((?:[^"\\]|\\.)*)"/i.exec(header);
  if (quoted) {
    return quoted[1].replace(/\\(.)/g, '$1') || null;
  }
  const bare = /filename\s*=\s*([^;]+)/i.exec(header);
  return bare ? bare[1].trim() || null : null;
}

function revokeObjectUrl(url: string): void {
  try {
    URL.revokeObjectURL(url);
  } catch {
    // Cleanup must never turn a successful download into an application error.
  }
}

function scheduleRevoke(url: string, delayMs: number): void {
  setTimeout(() => revokeObjectUrl(url), Math.max(0, delayMs));
}

function createBlobUrl(blob: Blob): string {
  return URL.createObjectURL(blob);
}

/** Saves a Blob with an anchor and revokes its object URL after a safe delay. */
export function saveBlob(
  blob: Blob,
  filename: string | null | undefined,
  fallbackFilename: string,
  revokeDelayMs = 10_000,
): void {
  const url = createBlobUrl(blob);
  let anchor: HTMLAnchorElement | null = null;
  try {
    anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = resolveBlobFilename(filename, fallbackFilename);
    anchor.style.display = 'none';
    document.body.appendChild(anchor);
    scheduleRevoke(url, revokeDelayMs);
    anchor.click();
  } catch (error) {
    revokeObjectUrl(url);
    throw error;
  } finally {
    anchor?.remove();
  }
}

/** Opens a Blob in a new tab, falling back to a download when popups are blocked. */
export function openBlob(
  blob: Blob,
  filename: string | null | undefined,
  fallbackFilename: string,
  revokeDelayMs = DEFAULT_BLOB_REVOKE_DELAY_MS,
): void {
  const url = createBlobUrl(blob);
  let anchor: HTMLAnchorElement | null = null;
  try {
    const opened = window.open(url, '_blank');
    scheduleRevoke(url, revokeDelayMs);
    if (!opened) {
      anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = resolveBlobFilename(filename, fallbackFilename);
      anchor.style.display = 'none';
      document.body.appendChild(anchor);
      anchor.click();
    }
  } catch (error) {
    revokeObjectUrl(url);
    throw error;
  } finally {
    anchor?.remove();
  }
}
