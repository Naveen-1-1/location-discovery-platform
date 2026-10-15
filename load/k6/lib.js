// What the scenarios share: the origins, the search mix, simulated clients and the summary file.
import http from "k6/http";
import { SharedArray } from "k6/data";

export const API = __ENV.API || "http://api:8080";
export const LIVE = __ENV.LIVE || "http://live-hub:8081";
const WORK = __ENV.WORK || "/work";

// Centres of 150 m squares that hold places, in a fixed random order (tools/load-tests.py writes them). Squares
// 150 m apart never share an H3 resolution-10 cell (at most 132 m across), so two origins never share a result.
// The first is kept for L5 alone.
export const ORIGINS = new SharedArray("origins", () => JSON.parse(open(`${WORK}/origins.json`)));
export const L5_ORIGIN = 0;

// Every search waits at most 10 s; the api's own deadline is 1.5 s.
export const PARAMS = { timeout: "10s" };

/**
 * A simulated client: X-Forwarded-For with an address from 198.18.0.0/15 (set aside for benchmarks). The api and
 * live-hub trust the header from the stack's private network, as they would from Caddy, so each address gets its own
 * rate limit.
 */
export function client(n) {
  return { "X-Forwarded-For": `198.18.${(n >> 8) & 255}.${n & 255}` };
}

export function nearbyUrl(origin, mode, category) {
  let url = `${API}/api/places/nearby?lat=${origin[0].toFixed(5)}&lon=${origin[1].toFixed(5)}`;
  if (mode) url += `&mode=${mode}`;
  if (category) url += `&category=${category}`;
  return url;
}

const MODES = [
  ["walk", 0.5],
  ["bike", 0.2],
  ["drive", 0.2],
  [null, 0.1], // straight-line distance
];
const CATEGORIES = ["food", "grocery", "pharmacy", "park", "bank", "transit", "bike_share"];

function pick(weighted, r) {
  for (const [value, weight] of weighted) {
    if (r < weight) return value;
    r -= weight;
  }
  return weighted[weighted.length - 1][0];
}

/**
 * The everyday mix (L1, L3, L4): one of EVERYDAY_POOL popular origins, a mode (half walking), and half the time one
 * category. About 300 × 4 × 8 distinct searches, so many repeat within the 5-minute result sharing.
 */
export const EVERYDAY_POOL = 300;

export function everydaySearch() {
  const origin = ORIGINS[1 + Math.floor(Math.random() * Math.min(EVERYDAY_POOL, ORIGINS.length - 1))];
  const mode = pick(MODES, Math.random());
  const category = Math.random() < 0.5 ? null : CATEGORIES[Math.floor(Math.random() * CATEGORIES.length)];
  return { url: nearbyUrl(origin, mode, category), tags: { mode: mode || "none" } };
}

export function get(url, headers, tags) {
  return http.get(url, Object.assign({}, PARAMS, { headers, tags }));
}

/** The k6 summary, written for the driver; the console keeps k6's own text summary. */
export function summary(name) {
  return (data) => ({
    [`${WORK}/${name}.summary.json`]: JSON.stringify(data, null, 1),
    stdout: `${name}: summary written to ${name}.summary.json\n`,
  });
}

export const TREND_STATS = ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)", "count"];
