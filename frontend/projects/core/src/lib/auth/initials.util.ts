/**
 * Derives up to two uppercase initials from a person's name, for avatar chips.
 *
 * Takes the first letter of the first and last name parts ("Asha Kumari" -> "AK"),
 * falling back to the first two characters of a single word ("Asha" -> "AS").
 * Names that carry no letters at all — e.g. a mobile number used as a username —
 * fall back to the first two characters as-is ("9876543210" -> "98"), so the
 * avatar is never blank.
 */
export function initialsOf(name: string | null | undefined): string {
  const trimmed = (name ?? '').trim();
  if (!trimmed) {
    return '?';
  }
  const parts = trimmed.split(/\s+/).filter((part) => part.length > 0);
  if (parts.length > 1) {
    return (parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
  }
  return parts[0].slice(0, 2).toUpperCase();
}
