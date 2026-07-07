import { Routes } from '@angular/router';
import { createAuthGuard } from 'core';
import { LoginComponent } from './auth/login.component';
import { RegisterComponent } from './auth/register.component';
import { AccountComponent } from './account/account.component';
import { AccountOrdersComponent } from './account/account-orders.component';
import { AccountAddressesComponent } from './account/account-addresses.component';
import { AccountProfileComponent } from './account/account-profile.component';
import { HomeComponent } from './home/home.component';
import { ShopComponent } from './shop/shop.component';
import { ProductDetailComponent } from './catalog/product-detail.component';
import { CartComponent } from './cart/cart.component';
import { WishlistComponent } from './wishlist/wishlist.component';
import { SharedWishlistComponent } from './wishlist/shared-wishlist.component';
import { CheckoutComponent } from './checkout/checkout.component';
import { TrackComponent } from './track/track.component';
import { AboutComponent } from './pages/about.component';
import { ContactComponent } from './pages/contact.component';

/** Guard requiring an authenticated customer; redirects to the login page. */
export const customerAuthGuard = createAuthGuard('/login');

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'shop', component: ShopComponent },
  // SEO-friendly product URL: id is authoritative, the trailing slug is cosmetic.
  { path: 'products/:id', component: ProductDetailComponent },
  { path: 'products/:id/:slug', component: ProductDetailComponent },
  { path: 'cart', component: CartComponent },
  { path: 'wishlist', component: WishlistComponent },
  { path: 'wishlist/shared/:token', component: SharedWishlistComponent },
  { path: 'checkout', component: CheckoutComponent },
  { path: 'track', component: TrackComponent },
  { path: 'track/:orderCode', component: TrackComponent },
  { path: 'about', component: AboutComponent },
  { path: 'contact', component: ContactComponent },
  { path: 'login', component: LoginComponent },
  { path: 'register', component: RegisterComponent },
  { path: 'account', component: AccountComponent, canActivate: [customerAuthGuard] },
  {
    path: 'account/orders',
    component: AccountOrdersComponent,
    canActivate: [customerAuthGuard],
  },
  {
    path: 'account/addresses',
    component: AccountAddressesComponent,
    canActivate: [customerAuthGuard],
  },
  {
    path: 'account/profile',
    component: AccountProfileComponent,
    canActivate: [customerAuthGuard],
  },
  { path: '**', redirectTo: '' },
];
