import test from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { hashToken, tokenMatches, isValidTripId } from "../functions/token-utils.mjs";

test("a stored SHA-256 hash matches only the exact QR token", () => {
  const token = randomBytes(32).toString("base64url");
  const storedHash = hashToken(token).toString("hex");
  assert.equal(tokenMatches(storedHash, token), true);
  assert.equal(tokenMatches(storedHash, token + "x"), false);
  assert.equal(tokenMatches(storedHash, ""), false);
});

test("malformed or missing hashes and tokens are rejected", () => {
  const token = randomBytes(32).toString("base64url");
  assert.equal(tokenMatches("", token), false);
  assert.equal(tokenMatches("not-a-sha256-digest", token), false);
  assert.equal(tokenMatches(null, token), false);
  assert.equal(tokenMatches("a".repeat(64), null), false);
  assert.equal(tokenMatches("a".repeat(64), "short"), false);
});

test("trip ID validation accepts server IDs and rejects malformed IDs", () => {
  assert.equal(isValidTripId("GF-123E4567-E89B-12D3-A456-426614174000"), true);
  assert.equal(isValidTripId("GF-not-a-trip"), false);
  assert.equal(isValidTripId("../trips/other-user"), false);
  assert.equal(isValidTripId(null), false);
});
