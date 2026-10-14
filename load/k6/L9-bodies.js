// L9: 10 MB bodies and deeply nested JSON sent to the api and to live-hub's batch endpoint, 10 a second for a minute.
// Pass: each is refused with 413 (too big) or 400 (too deep) in under 50 ms; the driver checks the heap stayed flat.
import { check } from "k6";
import exec from "k6/execution";
import http from "k6/http";
import { API, client, LIVE, summary, TREND_STATS } from "./lib.js";

const TEN_MB = "x".repeat(10 * 1024 * 1024);
// 20,000 nested arrays: under live-hub's 64 KiB limit, far over its nesting limit of 8.
const DEEP = "[".repeat(20000) + "]".repeat(20000);

const CASES = [
  { name: "api_10mb", url: `${API}/api/places/nearby?lat=42.355&lon=-71.0656`, body: TEN_MB, expect: 413 },
  { name: "api_nested", url: `${API}/api/places/nearby?lat=42.355&lon=-71.0656`, body: DEEP, expect: 413 },
  { name: "live_10mb", url: `${LIVE}/internal/live/batch`, body: TEN_MB, expect: 413 },
  { name: "live_nested", url: `${LIVE}/internal/live/batch`, body: DEEP, expect: 400 },
];

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    // Few users: each holds its own 10 MB body in memory.
    bodies: { executor: "constant-arrival-rate", rate: 10, timeUnit: "1s", duration: __ENV.DURATION || "1m",
              preAllocatedVUs: 5, maxVUs: 20 },
  },
  thresholds: {
    checks: ["rate==1"],
    http_req_duration: ["p(99)<50"],
    "http_req_duration{case:api_10mb}": ["p(99)<50"],
    "http_req_duration{case:api_nested}": ["p(99)<50"],
    "http_req_duration{case:live_10mb}": ["p(99)<50"],
    "http_req_duration{case:live_nested}": ["p(99)<50"],
  },
};

export default function () {
  const i = exec.scenario.iterationInTest;
  const c = CASES[i % CASES.length];
  const res = http.post(c.url, c.body, {
    headers: Object.assign({ "Content-Type": "application/json" }, client(i % 1000)),
    tags: { case: c.name },
    timeout: "10s",
  });
  check(res, { [`${c.name} answers ${c.expect}`]: (r) => r.status === c.expect }, { case: c.name });
}

export const handleSummary = summary("L9");
