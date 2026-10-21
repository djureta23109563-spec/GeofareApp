# GeoFare secure QR confirmation backend

This folder provides a cross-device trip confirmation flow for the Android app.

## Flow

1. Android calls `createPassengerTrip` in Firebase Functions using the signed-in passenger's Firebase session.
2. The function creates the canonical trip ID, creates a random 256-bit QR token, stores only its SHA-256 digest and a server-side 10-minute expiry, then returns the trip ID and raw token once.
3. The QR opens the separate Firebase Hosting site `https://geofare-trip-confirmation.web.app/confirm.html?tripId=...&token=...`.
4. The driver signs in with Firebase Authentication. Cloud Functions require the server-managed custom claim `driver: true`.
5. `lookupTripForDriver` validates the one-time token and expiry before returning a minimal trip summary. `confirmTrip` rechecks everything in a Firestore transaction and changes PENDING to CONFIRMED. Only the first confirmation succeeds.
6. Firestore rules prohibit clients from creating trips or setting CONFIRMED directly. They allow a passenger to update a narrow set of monitoring fields after confirmation.

No driver role is assigned by the website. Do not add a client-side role selector or trust a `role` field writable by the user.

## One-time setup

Requirements: Firebase CLI, Node.js 20, permission to administer the existing `geofare-79da3` Firebase project, and billing enabled if your Firebase Functions plan requires it.

From this folder:

```bash
npm install
npx firebase login
npx firebase use geofare-79da3
```

Create a **separate Hosting site** so the existing `geofare-79da3.web.app` site is not overwritten:

```bash
npx firebase hosting:sites:create geofare-trip-confirmation --project geofare-79da3
```

In Firebase Console → Project settings → General, register a **Web app** in project `geofare-79da3`. Copy that web app's `appId` into `public/firebase-config.js` (replace the `REPLACE_WITH_WEB_APP_ID_FROM_FIREBASE_CONSOLE` value). Also add `geofare-trip-confirmation.web.app` under Authentication → Settings → Authorized domains if it is not already listed. The website intentionally refuses to initialize while that value is still a placeholder.

Verify Email/Password sign-in is enabled in Firebase Console → Authentication → Sign-in method. Create one account for the driver in Firebase Authentication. On the machine used for administration, configure Application Default Credentials or set `GOOGLE_APPLICATION_CREDENTIALS` to a service-account JSON kept outside the repository. Never commit that file.

Grant the driver claim using the driver's Firebase Authentication UID (not their email address):

```node
node scripts/grant-driver.mjs FIREBASE_AUTH_UID
```

The account must sign out and back in after its custom claim is set.

Deploy the hosting page, callable functions, and the included Firestore rules:

```bash
npx firebase deploy --only functions,hosting,firestore:rules --project geofare-79da3
```

If the project has not registered the needed APIs, follow the Firebase CLI prompts. Review the functions region (`asia-southeast1`) and billing requirements. Do not deploy to production until you have reviewed the proposed Firestore rules against any other screens that write to Firestore.

## Two-phone test

1. On Phone A, sign in to GeoFare with a passenger account and create a trip. Confirm that the QR appears only after the callable succeeds.
2. On Phone B, open the camera / Google Lens and scan the QR. It should open the driver confirmation page. Sign in with a separately registered driver account whose `driver: true` claim was provisioned by an administrator.
3. Check that the plate, pickup, destination, route distance, and fare match Phone A. Tap **Confirm this trip**.
4. Confirm that Phone B reports success and Phone A leaves the waiting screen after its Firestore listener observes `status = CONFIRMED`.
5. Scan the same QR again: it must be rejected as already confirmed. Wait longer than 10 minutes before scanning a new pending trip: the old QR must be rejected as expired.
6. Sign in on Phone B with a normal passenger account: it must be denied driver access. Try a changed trip ID or token: it must be rejected.
7. Turn off the network while creating a trip: no QR should be shown. Reconnect and retry.
8. Verify Firebase Console → Firestore → `trips/{tripId}`: `passengerUid`, `driverUid`, `status`, `confirmedAt`, and `qrExpiresAt` should match the scenario. The raw token must never be stored.

## Important boundaries

- The callable creates the trip record and protects driver confirmation, but fare and route distance are still supplied by the Android client. For fare integrity in production, move approved fare calculation/route validation server-side too.
- The current Android route monitor tracks the passenger device; it is not an independent driver/vehicle GPS feed.
- The separate `GeofareConfirmTripWebsite` repository is empty at the time of implementation. The confirmation page is therefore maintained in this repository under `firebase-backend/public`.
- Deployment and physical-device tests have not been executed from this coding session. Firebase project permissions, Authentication providers, Hosting site creation, rules compatibility, and Functions deployment must be verified using the steps above.
