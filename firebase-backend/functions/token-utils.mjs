import { createHash, timingSafeEqual } from "node:crypto";

export function hashToken(token) {
  return createHash("sha256").update(token, "utf8").digest();
}

export function tokenMatches(storedHex, token) {
  if (typeof storedHex !== "string" || !/^[0-9a-f]{64}$/i.test(storedHex)) return false;
  if (typeof token !== "string" || token.length < 40 || token.length > 100) return false;
  const expected = Buffer.from(storedHex, "hex");
  const actual = hashToken(token);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

export function isValidTripId(value) {
  return typeof value === "string" && /^GF-[0-9A-F-]{20,60}$/i.test(value);
}
