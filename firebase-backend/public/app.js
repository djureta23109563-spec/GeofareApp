import { initializeApp } from "https://www.gstatic.com/firebasejs/11.10.0/firebase-app.js";
import {
  getAuth,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signOut,
  getIdTokenResult
} from "https://www.gstatic.com/firebasejs/11.10.0/firebase-auth.js";
import { getFunctions, httpsCallable } from "https://www.gstatic.com/firebasejs/11.10.0/firebase-functions.js";

const config = window.GEOFARE_FIREBASE_CONFIG;
const statusEl = document.querySelector("#status");
const loginForm = document.querySelector("#login-form");
const loginButton = document.querySelector("#login-button");
const signoutButton = document.querySelector("#signout-button");
const tripCard = document.querySelector("#trip-card");
const confirmButton = document.querySelector("#confirm-button");

const params = new URLSearchParams(window.location.search);
const tripId = params.get("tripId") || "";
const token = params.get("token") || "";
// Do not leave the bearer token visible in the address bar/history longer than
// necessary. The page keeps it only in memory; it is not placed in localStorage.
window.history.replaceState({}, document.title, window.location.pathname);

let auth;
let lookupTripForDriver;
let confirmTrip;
let previewedTrip = null;
let confirming = false;

function showStatus(message, kind = "") {
  statusEl.textContent = message;
  statusEl.className = kind ? `status ${kind}` : "status";
}
function errorMessage(error) {
  const code = error?.code || "";
  if (code.includes("permission-denied")) {
    return "This account is not an approved driver, or this QR code is invalid. Sign in with an administrator-approved driver account.";
  }
  if (code.includes("deadline-exceeded")) {
    return "This QR code has expired. Ask the passenger to generate a new trip QR code.";
  }
  if (code.includes("failed-precondition")) {
    return error.message || "This trip is already confirmed or cannot be confirmed.";
  }
  if (code.includes("unauthenticated")) return "Please sign in to an approved driver account.";
  if (code.includes("not-found")) return "No trip matches this QR code. Ask the passenger to generate a new one.";
  return error?.message || "The request failed. Check the connection and try again.";
}
function setBusy(busy) {
  loginButton.disabled = busy;
  confirmButton.disabled = busy;
}
function formatMoney(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) return "Unavailable";
  return new Intl.NumberFormat("en-PH", { style: "currency", currency: "PHP" }).format(value);
}
function renderTrip(trip) {
  previewedTrip = trip;
  document.querySelector("#trip-id").textContent = trip.tripId;
  document.querySelector("#plate").textContent = trip.plate || "UNKNOWN";
  document.querySelector("#pickup").textContent = trip.pickupAddress || "Not provided";
  document.querySelector("#destination").textContent = trip.destinationAddress || "Not provided";
  document.querySelector("#distance").textContent =
    typeof trip.distanceKm === "number" ? `${trip.distanceKm.toFixed(2)} km` : "Unavailable";
  document.querySelector("#fare").textContent = formatMoney(trip.fare);
  document.querySelector("#expires").textContent =
    trip.expiresAt ? new Date(trip.expiresAt).toLocaleString() : "Soon";
  tripCard.hidden = false;
  confirmButton.hidden = false;
}
async function loadTrip() {
  tripCard.hidden = true;
  if (!tripId || !token) {
    showStatus("No complete trip QR was found. Scan the passenger's current QR code again.");
    return;
  }
  showStatus("Checking the QR code against GeoFare…");
  const result = await lookupTripForDriver({ tripId, token });
  renderTrip(result.data);
  showStatus("Trip verified. Check the plate, destination, and estimated fare before confirming.", "success");
}

if (!config || !config.apiKey || !config.projectId || !config.authDomain || !config.appId || config.appId.startsWith("REPLACE_")) {
  showStatus("Firebase web configuration is incomplete. Check firebase-backend/public/firebase-config.js.");
  loginForm.hidden = true;
} else {
  const app = initializeApp(config);
  auth = getAuth(app);
  const functions = getFunctions(app, "asia-southeast1");
  lookupTripForDriver = httpsCallable(functions, "lookupTripForDriver");
  confirmTrip = httpsCallable(functions, "confirmTrip");

  loginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    setBusy(true);
    showStatus("Signing in…");
    try {
      await signInWithEmailAndPassword(
        auth,
        document.querySelector("#email").value.trim(),
        document.querySelector("#password").value
      );
    } catch (error) {
      showStatus(errorMessage(error), "error");
      setBusy(false);
    }
  });

  signoutButton.addEventListener("click", async () => {
    await signOut(auth);
    tripCard.hidden = true;
    showStatus("Signed out. Sign in with an approved driver account.");
  });

  confirmButton.addEventListener("click", async () => {
    if (confirming || !previewedTrip) return;
    confirming = true;
    setBusy(true);
    showStatus("Confirming trip securely…");
    try {
      const result = await confirmTrip({ tripId, token });
      tripCard.hidden = true;
      showStatus(
        `Trip ${result.data.tripId} is confirmed. The passenger's app will receive the status update automatically.`,
        "success"
      );
      previewedTrip = null;
      confirmButton.hidden = true;
    } catch (error) {
      showStatus(errorMessage(error), "error");
      // A stale preview is not reusable after an invalid/expired/used QR.
      previewedTrip = null;
      tripCard.hidden = true;
    } finally {
      confirming = false;
      setBusy(false);
    }
  });

  onAuthStateChanged(auth, async (user) => {
    setBusy(false);
    tripCard.hidden = true;
    previewedTrip = null;
    loginForm.hidden = Boolean(user);
    signoutButton.hidden = !user;
    if (!user) {
      showStatus(tripId && token
        ? "Sign in with an approved GeoFare driver account to validate this QR."
        : "Scan the passenger's current trip QR code to continue.");
      return;
    }
    showStatus("Verifying driver permissions…");
    try {
      const idToken = await getIdTokenResult(user, true);
      if (idToken.claims.driver !== true) {
        showStatus("This account is signed in, but it is not an approved driver. Sign out and use a driver account provisioned by the GeoFare administrator.", "error");
        return;
      }
      await loadTrip();
    } catch (error) {
      showStatus(errorMessage(error), "error");
    }
  });
}
