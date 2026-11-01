import { Role } from 'core';

/** A single navigable link (either standalone or a child inside a group). */
export interface NavLink {
  kind: 'link';
  label: string;
  path: string;
  icon: string;
  disabled?: boolean;
  /** When true, the item is only shown to ADMIN users (Req 5.4). */
  adminOnly?: boolean;
  /**
   * When set, the item is shown only to users whose role is in this list
   * (evaluated in addition to {@link adminOnly}). Lets non-admin roles such as
   * SALESPERSON see specific entries (e.g. New Order, Req 7) without widening
   * the admin-only default.
   */
  roles?: Role[];
}

/** A collapsible group of related links rendered as a labelled section in the
 *  hamburger drawer. Group visibility follows its children's roles. */
export interface NavGroup {
  kind: 'group';
  label: string;
  icon: string;
  children: NavLink[];
}

/** A top-level navigation entry: either a standalone link or a group. */
export type NavEntry = NavLink | NavGroup;

/** One destination on the persistent bottom tab bar (Req 2). */
export interface BottomTab {
  label: string;
  path: string;
  icon: string;
}
