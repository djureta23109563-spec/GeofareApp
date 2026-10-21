import { applicationDefault, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";

const uid = process.argv[2]?.trim();

if (!uid) {
  console.error("Usage: node scripts/grant-driver.mjs <FIREBASE_AUTH_UID>");
  process.exit(2);
}

initializeApp({
  credential: applicationDefault(),
  projectId: "geofare-79da3"
});

const auth = getAuth();
const user = await auth.getUser(uid);
const existingClaims = user.customClaims ?? {};

await auth.setCustomUserClaims(uid, {
  ...existingClaims,
  driver: true
});

console.log(`Granted the GeoFare driver claim to UID ${uid}.`);
console.log("The user must sign out and sign in again, or refresh the ID token, before the claim appears.");
