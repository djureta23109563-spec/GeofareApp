import { randomBytes, randomUUID, createHash, timingSafeEqual } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { setGlobalOptions } from "firebase-functions/v2";
import { HttpsError, onCall } from "firebase-functions/v2/https";

initializeApp();
setGlobalOptions({ region: "asia-southeast1", maxInstances: 20 });

const db = getFirestore();
const QR_TTL_MS = 10 * 60 * 1000;

function requireUser(request) {
  if (!request.auth?.uid) {
    throw new HttpsError("unauthenticated", "Sign in to continue.");
  }
  return request.auth;
}

function requireApprovedDriver(request) {
  const auth = requireUser(request);
  if (auth.token.driver !== true) {
    throw new HttpsError(
      "permission-denied",
      "This account is not an approved GeoFare driver. Contact the GeoFare administrator."
    );
  }
  return auth;
}

function cleanString(value, label, maxLength = 180) {
  if (typeof value !== "string") {
    throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  }
  const clean = value.trim();
  if (!clean || clean.length > maxLength) {
    throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  }
  return clean;
}

function finiteNumber(value, label, min, max) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max) {
    throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  }
  return value;
}

function optionalFiniteNumber(value, label) {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new HttpsError("invalid-argument", `Invalid ${label}.`);
  }
  return value;
}

function hashToken(token) {
  return createHash("sha256").update(token, "utf8").digest();
}

function tokenMatches(storedHex, token) {
  if (typeof storedHex !== "string" || !/^[0-9a-f]{64}$/i.test(storedHex)) return false;
  if (typeof token !== "string" || token.length < 40 || token.length > 100) return false;
  const expected = Buffer.from(storedHex, "hex");
  const actual = hashToken(token);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

function getTripToken(request) {
  const tripId = cleanString(request.data?.tripId, "trip identifier", 100);
  const token = cleanString(request.data?.token, "QR token", 120);
  if (!/^GF-[0-9A-F-]{20,60}$/i.test(tripId)) {
    throw new HttpsError("invalid-argument", "This QR code is invalid. Scan the trip QR again.");
  }
  return { tripId, token };
}

function ensurePendingAndNotExpired(trip) {
  if (trip.status !== "PENDING") {
    throw new HttpsError(
      "failed-precondition",
      trip.status === "CONFIRMED"
        ? "This trip has already been confirmed."
        : "This trip is no longer available for confirmation."
    );
  }
  const expiry = trip.qrExpiresAt;
  if (!expiry || typeof expiry.toMillis !== "function" || expiry.toMillis() <= Date.now()) {
    throw new HttpsError("deadline-exceeded", "This trip QR code has expired. Ask the passenger to create a new trip.");
  }
}

function verifyTripToken(trip, token) {
  if (!tokenMatches(trip.qrTokenHash, token)) {
    throw new HttpsError("permission-denied", "This QR code is invalid or has expired. Scan the passenger's current trip QR.");
  }
}

function publicTripDetails(tripId, trip) {
  return {
    tripId,
    plate: trip.plate ?? "UNKNOWN",
    pickupAddress: trip.pickupAddress ?? "Pickup not provided",
    destinationAddress: trip.destinationAddress ?? "Destination not provided",
    distanceKm: trip.distanceKm,
    fare: trip.fare,
    status: trip.status,
    expiresAt: trip.qrExpiresAt.toDate().toISOString()
  };
}

/**
 * Creates the trip and one-time QR token in the trusted backend.
 * The raw token is returned to the passenger once; only its SHA-256 hash
 * is persisted. The expiry time is determined by the server clock.
 */
export const createPassengerTrip = onCall(async (request) => {
  const auth = requireUser(request);
  if (auth.token.driver === true) {
    throw new HttpsError("permission-denied", "Driver accounts cannot create passenger trips.");
  }

  const data = request.data ?? {};
  const plate = cleanString(data.plate, "plate number", 32);
  const pickupLat = finiteNumber(data.pickupLat, "pickup latitude", -90, 90);
  const pickupLng = finiteNumber(data.pickupLng, "pickup longitude", -180, 180);
  const destinationLat = finiteNumber(data.destinationLat, "destination latitude", -90, 90);
  const destinationLng = finiteNumber(data.destinationLng, "destination longitude", -180, 180);

  if ((pickupLat === 0 && pickupLng === 0) || (destinationLat === 0 && destinationLng === 0)) {
    throw new HttpsError("invalid-argument", "GeoFare needs a valid pickup and destination before creating a trip.");
  }

  const pickupAddress = cleanString(data.pickupAddress, "pickup address", 300);
  const destinationAddress = cleanString(data.destinationAddress, "destination address", 300);
  const distanceKm = finiteNumber(data.distanceKm, "route distance", 0.001, 1000);
  const fare = finiteNumber(data.fare, "fare", 0, 1000000);
  const passengerTimestamp = cleanString(data.passengerTimestamp, "trip timestamp", 80);

  const optionalFields = {
    pickupAcquiredAtEpochMs: optionalFiniteNumber(data.pickupAcquiredAtEpochMs, "pickup timing"),
    destinationConfirmedAtEpochMs: optionalFiniteNumber(data.destinationConfirmedAtEpochMs, "destination timing"),
    fareComputationStartEpochMs: optionalFiniteNumber(data.fareComputationStartEpochMs, "fare timing"),
    fareDisplayedAtEpochMs: optionalFiniteNumber(data.fareDisplayedAtEpochMs, "fare display timing"),
    fareComputationLatencyMs: optionalFiniteNumber(data.fareComputationLatencyMs, "fare latency")
  };

  const tripId = `GF-${randomUUID().toUpperCase()}`;
  const token = randomBytes(32).toString("base64url");
  const now = Date.now();
  const qrExpiresAt = Timestamp.fromMillis(now + QR_TTL_MS);

  const tripData = {
    tripId,
    passengerUid: auth.uid,
    plate,
    pickupLat,
    pickupLng,
    pickupAddress,
    destinationLat,
    destinationLng,
    destinationAddress,
    distanceKm,
    fare,
    passengerTimestamp,
    status: "PENDING",
    routeStatus: "WAITING_FOR_DRIVER",
    qrTokenHash: hashToken(token).toString("hex"),
    qrIssuedAt: FieldValue.serverTimestamp(),
    qrExpiresAt,
    createdAt: FieldValue.serverTimestamp(),
    ...Object.fromEntries(Object.entries(optionalFields).filter(([, value]) => value !== undefined))
  };

  await db.collection("trips").doc(tripId).create(tripData);

  return {
    tripId,
    token,
    expiresAt: qrExpiresAt.toDate().toISOString()
  };
});

/**
 * Only an administrator-provisioned driver may inspect a trip through the QR.
 * The function returns only the fields needed to confirm the passenger trip.
 */
export const lookupTripForDriver = onCall(async (request) => {
  const auth = requireApprovedDriver(request);
  const { tripId, token } = getTripToken(request);
  const snapshot = await db.collection("trips").doc(tripId).get();

  if (!snapshot.exists) {
    throw new HttpsError("not-found", "No trip matches this QR code.");
  }

  const trip = snapshot.data();
  if (trip.passengerUid === auth.uid) {
    throw new HttpsError("permission-denied", "A passenger account cannot act as the driver for its own trip.");
  }

  verifyTripToken(trip, token);
  ensurePendingAndNotExpired(trip);
  return publicTripDetails(tripId, trip);
});

/**
 * Atomically confirms a pending, unexpired trip. Firestore transaction
 * serializes simultaneous scans; only the first confirmation can succeed.
 */
export const confirmTrip = onCall(async (request) => {
  const auth = requireApprovedDriver(request);
  const { tripId, token } = getTripToken(request);
  const tripRef = db.collection("trips").doc(tripId);

  return db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(tripRef);
    if (!snapshot.exists) {
      throw new HttpsError("not-found", "No trip matches this QR code.");
    }

    const trip = snapshot.data();
    if (trip.passengerUid === auth.uid) {
      throw new HttpsError("permission-denied", "A passenger account cannot confirm its own trip as the driver.");
    }

    verifyTripToken(trip, token);
    ensurePendingAndNotExpired(trip);

    transaction.update(tripRef, {
      status: "CONFIRMED",
      routeStatus: "DRIVER_CONFIRMED",
      driverUid: auth.uid,
      confirmedAt: FieldValue.serverTimestamp()
    });

    return {
      tripId,
      status: "CONFIRMED",
      plate: trip.plate ?? "UNKNOWN",
      fare: trip.fare,
      destinationAddress: trip.destinationAddress ?? "Destination not provided"
    };
  });
});
