// L13: a soak. The popular mix (most searches from popular areas, NEW_SHARE of them never made recently) at a steady
// RATE for MINUTES, judged in 10-minute windows by L1's line: p99 < 600 ms, errors other than 429 < 0.5%, under 1%
// of travel-time searches in straight-line order. The driver adds live viewers and watches memory over the run.
import exec from "k6/execution";
import { Counter, Rate } from "k6/metrics";
import { client, get, popularSearch, summary, TREND_STATS } from "./lib.js";

const errors = new Rate("errors_not_429");
const fallbacks = new Rate("fallbacks");
const limited = new Counter("limited_429");
const rejected = new Counter("rejected_503");

const RATE = Number(__ENV.RATE || 200);
const MINUTES = Number(__ENV.MINUTES || 60);
const NEW_SHARE = Number(__ENV.NEW_SHARE || 0.05);
const WINDOW = Number(__ENV.WINDOW_SECONDS || 600);
const WINDOWS = Math.ceil((MINUTES * 60) / WINDOW);

const thresholds = {
  "http_req_duration": ["p(99)<600"],
  errors_not_429: ["rate<0.005"],
  fallbacks: ["rate<0.01"],
  limited_429: ["count==0"],
  rejected_503: ["count>=0"],
};
for (let w = 0; w < WINDOWS; w++) {
  thresholds[`http_req_duration{w:${w}}`] = ["p(99)<600"];
  thresholds[`errors_not_429{w:${w}}`] = ["rate<0.005"];
  thresholds[`fallbacks{w:${w}}`] = ["rate<0.01"];
}

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    soak: { executor: "constant-arrival-rate", rate: RATE, timeUnit: "1s", duration: `${MINUTES}m`,
            preAllocatedVUs: 200, maxVUs: 2000 },
  },
  thresholds,
};

export default function () {
  const w = Math.floor((Date.now() - exec.scenario.startTime) / 1000 / WINDOW);
  const search = popularSearch(exec.scenario.iterationInTest, NEW_SHARE);
  const tags = Object.assign({ w: String(w) }, search.tags);
  // 3,000 clients, as L3 and L11: at 500/s each sends 0.17 a second, far under the per-client limit.
  const res = get(search.url, client(exec.scenario.iterationInTest % 3000), tags);
  errors.add(res.status !== 200 && res.status !== 429, tags);
  if (res.status === 200 && search.tags.mode !== "none") fallbacks.add(res.json("fallback") !== null, tags);
  if (res.status === 429) limited.add(1, tags);
  if (res.status === 503) rejected.add(1, tags);
}

export const handleSummary = summary("L13");
