// L2: only searches nobody made before (each origin and mode once), 20 a second for 5 minutes.
// Pass: p95 < 500 ms; errors other than 429 < 0.5% and under 1% fallbacks, as in L1.
import exec from "k6/execution";
import { Rate } from "k6/metrics";
import { client, EVERYDAY_POOL, get, nearbyUrl, ORIGINS, summary, TREND_STATS } from "./lib.js";

const errors = new Rate("errors_not_429");
const fallbacks = new Rate("fallbacks");
const RATE = Number(__ENV.RATE || 20);
const MINUTES = Number(__ENV.MINUTES || 5);
const MODES = ["walk", "bike", "drive"];

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    misses: { executor: "constant-arrival-rate", rate: RATE, timeUnit: "1s", duration: `${MINUTES}m`,
              preAllocatedVUs: 30, maxVUs: 300 },
  },
  thresholds: {
    http_req_duration: ["p(95)<500"],
    errors_not_429: ["rate<0.005"],
    fallbacks: ["rate<0.01"],
  },
};

export default function () {
  const i = exec.scenario.iterationInTest;
  // Not L5's origin nor the 300 the everyday mix uses, whose results may still be shared from an earlier scenario.
  const first = 1 + EVERYDAY_POOL;
  const n = ORIGINS.length - first;
  if (i >= n * MODES.length) {
    exec.test.abort(`only ${n * MODES.length} distinct searches; lower RATE or MINUTES`);
  }
  // Every origin with walking first, then every origin by bike, then by car: never the same key twice.
  const origin = ORIGINS[first + (i % n)];
  const mode = MODES[Math.floor(i / n)];
  const res = get(nearbyUrl(origin, mode, null), client(i % 500), { mode });
  errors.add(res.status !== 200 && res.status !== 429);
  if (res.status === 200) fallbacks.add(res.json("fallback") !== null);
}

export const handleSummary = summary("L2");
