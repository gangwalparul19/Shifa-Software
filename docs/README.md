# Shifa OMS Documentation

This folder contains client-facing documentation for the Shifa Order Management System.

## Available Documents

### 📄 Shifa-Pricing-Interactive.html
**Interactive pricing proposal** with tabs for comparison, modules, AMC, payment plan, and running costs.
- One-time build: ₹39,999
- Payment plan: ₹15K + ₹15K + ₹9,999
- AMC: ₹6,999 (Standard) / ₹9,999 (Growth)
- Opens in any browser, mobile-responsive

### 📖 Shifa-Features-Guide.html
**Complete features showcase** with descriptions, workflows, and screenshots (placeholders to be filled).
- 14 major sections covering all features
- Interactive sidebar navigation
- Mobile-first design
- Screenshot placeholders ready for real captures

### 📸 Screenshot-Guide.md
**Step-by-step guide** for capturing screenshots from the live application.
- Login credentials for all test roles
- 45-screenshot checklist organized by section
- Instructions for adding screenshots to Features Guide
- Estimated 3-4 hours to complete

### 🎨 Shifa-Client-Presentation.html
**Marketing presentation** (if exists) for initial client meetings.

## Usage

### For Client Presentations:
1. Open `Shifa-Pricing-Interactive.html` in browser
2. Walk through pricing tabs
3. Emphasize mobile-first, weekend training, payment plan

### For Training & Demos:
1. Complete screenshots using `Screenshot-Guide.md`
2. Open `Shifa-Features-Guide.html` in browser
3. Navigate section-by-section during training
4. Use as reference for client questions

### For Internal Reference:
- Keep these docs updated as features are added/changed
- Update pricing when costs change
- Re-capture screenshots after major UI updates

## Quick Links

- **Live Application:** http://13.207.62.222/dashboard
- **Admin Login:** admin / admin123
- **Salesperson Login:** sales1 / admin123
- **Source Code:** `../backend/` (Java/Spring) + `../frontend/` (Angular)

## Maintenance

When features change:
1. Update `Shifa-Features-Guide.html` section text
2. Re-capture affected screenshots
3. Update `Screenshot-Guide.md` checklist if new pages added
4. Commit changes to git

When pricing changes:
1. Update `Shifa-Pricing-Interactive.html` amounts
2. Recalculate year-one totals
3. Update payment plan split
4. Commit changes to git

## Screenshot Folder Structure

```
docs/
├── Shifa-Pricing-Interactive.html
├── Shifa-Features-Guide.html
├── Screenshot-Guide.md
├── README.md (this file)
└── screenshots/  ← Create this folder
    ├── admin-dashboard.png
    ├── salesperson-dashboard.png
    ├── order-detail.png
    ├── packing-queues.png
    ├── ... (45 total)
    └── mobile/  ← Mobile-specific shots
        ├── dashboard-mobile.png
        ├── bottom-tabs.png
        └── ...
```

## Next Steps

1. [ ] Create `screenshots/` folder
2. [ ] Follow `Screenshot-Guide.md` to capture all 45 screenshots
3. [ ] Update `Shifa-Features-Guide.html` to replace placeholders
4. [ ] Review complete guide in browser
5. [ ] Share with client for feedback
6. [ ] Iterate based on client questions

---

**Last Updated:** July 2026  
**Version:** 1.0  
**Maintainer:** Dev Team
