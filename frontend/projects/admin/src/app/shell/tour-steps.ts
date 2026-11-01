import { Role } from 'core';
import { TourStep } from '../shared/guided-tour.service';

/** The first-run tour's id (localStorage key namespace) + its steps. */
export const MAIN_TOUR_ID = 'main-shell-v1';
export const MAIN_TOUR_STEPS: TourStep[] = [
  {
    selector: '[data-tour="hamburger"]',
    title: 'Menu',
    body: 'Tap here any time to see every page you can access, grouped by area.',
    placement: 'bottom',
  },
  {
    selector: '[data-tour="global-search"]',
    title: 'Quick search',
    body: 'Find any order, product, or customer instantly. On a keyboard, press Ctrl+K (or Cmd+K) from anywhere.',
    placement: 'bottom',
  },
  {
    selector: '[data-tour="notif-bell"]',
    title: 'Notifications',
    body: 'Alerts addressed to you — new orders, approvals needed, follow-ups due — show up here.',
    placement: 'bottom',
  },
  {
    selector: '[data-tour="bottom-tabs"]',
    title: 'Your main tabs',
    body: 'The four screens you use most are always one tap away here.',
    placement: 'top',
  },
];

/**
 * Role-specific closing step appended to the generic tour, so each operational
 * role is told what their actual daily job is (the generic tour only covers the
 * shell chrome). Keyed by role; roles without an entry just get the generic tour.
 * The tour id is namespaced per role so a role sees its tailored tour once.
 */
export const ROLE_TOUR_STEP: Partial<Record<Role, TourStep>> = {
  [Role.PACKING_USER]: {
    selector: '[data-tour="bottom-tabs"]',
    title: 'Your packing day',
    body: 'Open Packing: print each label, Mark packed, then Hand over. QuikShip pickups and in-house deliveries track in their own sections below the queues.',
    placement: 'top',
  },
  [Role.PAYMENT_VERIFIER]: {
    selector: '[data-tour="bottom-tabs"]',
    title: 'Verifying payments',
    body: 'Payments is your home. Open each screenshot, check it matches the amount, then Verify or Reject with a note — this flags the order for the admin.',
    placement: 'top',
  },
  [Role.ACCOUNTANT]: {
    selector: '[data-tour="bottom-tabs"]',
    title: 'Collections & books',
    body: 'Reconcile settles collected COD and chases the courier for pending remittance; Reports and Expenses keep the books. Tap a dashboard tile to jump straight in.',
    placement: 'top',
  },
  [Role.SALESPERSON]: {
    selector: '[data-tour="bottom-tabs"]',
    title: 'Your sales day',
    body: 'Punch New Orders, track them in Orders, work your Leads and follow-ups, and watch your rank on the Leaderboard. Your dashboard shows today at a glance.',
    placement: 'top',
  },
  [Role.TEAM_LEAD]: {
    selector: '[data-tour="bottom-tabs"]',
    title: 'Your team at a glance',
    body: "Your dashboard and Orders show your whole team's orders; Performance ranks your salespeople and shows which lead sources convert best.",
    placement: 'top',
  },
};
