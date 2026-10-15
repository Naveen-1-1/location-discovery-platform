// L1: nearby at a steady 50 requests a second for 10 minutes, after a minute's warm-up at the same rate.
// Pass: p95 < 300 ms, p99 < 600 ms, errors other than 429 < 0.5%; and under 1% of travel-time searches fall back to
// straight-line order, or the numbers would not measure the routed path.
import exec from "k6/execution";
import { Rate } from "k6/metrics";
import { client, everydaySearch, get, summary, TREND_STATS } from "./lib.js";

const errors = new Rate("errors_not_429");
const fallbacks = new Rate("fallbacks");
const RATE = 50;
const MINUTES = Number(__ENV.MINUTES || 10);

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    warmup: { executor: "constant-arrival-rate", rate: RATE, timeUnit: "1s", duration: "1m",
              preAllocatedVUs: 50, maxVUs: 400, tags: { phase: "warmup" } },
    steady: { executor: "constant-arrival-rate", rate: RATE, timeUnit: "1s", duration: `${MINUTES}m`,
              startTime: "1m", preAllocatedVUs: 50, maxVUs: 400, tags: { phase: "steady" } },
  },
  thresholds: {
    "http_req_duration{phase:steady}": ["p(95)<300", "p(99)<600"],
    "errors_not_429{phase:steady}": ["rate<0.005"],
    "fallbacks{phase:steady}": ["rate<0.01"],
  },
};

export default function () {
  // 500 clients share the load: 0.1 requests a second each, far under the per-client limit.
  const search = everydaySearch();
  const res = get(search.url, client(exec.scenario.iterationInTest % 500), search.tags);
  errors.add(res.status !== 200 && res.status !== 429);
  if (res.status === 200 && search.tags.mode !== "none") fallbacks.add(res.json("fallback") !== null);
}

export const handleSummary = summary("L1");
