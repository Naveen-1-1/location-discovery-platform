// Everyday searches at a steady rate, the traffic that L10, C3 and C4 add their fault to. The driver times the fault
// and passes when it starts and ends (FAULT_FROM, FAULT_TO: epoch ms), so answers can be told apart by phase.
// Pass, per scenario: the driver reads this summary.
import exec from "k6/execution";
import { Counter, Rate, Trend } from "k6/metrics";
import { client, everydaySearch, get, summary, TREND_STATS } from "./lib.js";

const NAME = __ENV.NAME || "everyday";
const RATE = Number(__ENV.RATE || 50);
const FAULT_FROM = Number(__ENV.FAULT_FROM || 0);
const FAULT_TO = Number(__ENV.FAULT_TO || 0);

const errors = new Rate("errors_not_429");             // anything but 200 and 429, and 503 counted apart
const unflagged = new Counter("unflagged_straight_line"); // a travel-time search without travel times and no reason
const fallbackAfter = new Trend("fallback_after_ms");     // from the fault's start to each answer that fell back
const breakerAfter = new Trend("breaker_open_after_ms");  // ... and to each that fell back because the breaker opened
const refused = new Counter("refused_503");
const noRetryAfter = new Counter("refused_without_retry_after");

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    everyday: { executor: "constant-arrival-rate", rate: RATE, timeUnit: "1s", duration: __ENV.DURATION || "3m",
                preAllocatedVUs: 50, maxVUs: 600 },
  },
  // Listed so the summary has them; the driver judges.
  thresholds: {
    "http_req_duration{phase:before}": ["p(95)>=0"],
    "http_req_duration{phase:fault}": ["p(95)>=0"],
    "http_req_duration{phase:after}": ["p(95)>=0"],
    "http_req_duration{phase:fault,status:200}": ["p(99)>=0"],
    "errors_not_429{phase:fault}": ["rate>=0"],
  },
};

function phase(now) {
  if (!FAULT_FROM || now < FAULT_FROM) return "before";
  return !FAULT_TO || now < FAULT_TO ? "fault" : "after";
}

export default function () {
  const now = Date.now();
  const tags = { phase: phase(now) };
  const search = everydaySearch();
  const res = get(search.url, client(exec.scenario.iterationInTest % 500), Object.assign(tags, search.tags));
  errors.add(res.status !== 200 && res.status !== 429 && res.status !== 503, tags);
  if (res.status === 503) {
    refused.add(1, tags);
    if (!res.headers["Retry-After"]) noRetryAfter.add(1, tags);
  }
  if (res.status === 200 && search.tags.mode !== "none") {
    const fallback = res.json("fallback");
    if (res.json("travelTimeSource") === "straight_line" && !fallback) unflagged.add(1, tags);
    if (fallback && FAULT_FROM && now >= FAULT_FROM) {
      fallbackAfter.add(now - FAULT_FROM, tags);
      if (fallback === "breaker_open") breakerAfter.add(now - FAULT_FROM, tags);
    }
  }
}

export const handleSummary = summary(NAME);
