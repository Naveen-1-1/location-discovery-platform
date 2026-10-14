// L3: a spike from 10 to 300 requests a second in 30 s, held for a minute, then back to 10.
// Pass: only 200, 429 and 503 answers; accepted (200) p99 < 1 s; recovered within 30 s of the spike's end (from
// 30 s after it, p95 < 300 ms and no 503); no out-of-memory restart (the driver checks the container).
import exec from "k6/execution";
import { Counter } from "k6/metrics";
import { client, everydaySearch, get, summary, TREND_STATS } from "./lib.js";

const unexpected = new Counter("unexpected_status");
const rejected = new Counter("rejected_503");
// Seconds from the start: 60 s at 10/s, 30 s up, 60 s at 300/s, 10 s down; the spike ends at 160 s.
const SPIKE_END = 160;

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    spike: {
      executor: "ramping-arrival-rate", startRate: 10, timeUnit: "1s", preAllocatedVUs: 100, maxVUs: 1500,
      stages: [
        { target: 10, duration: "60s" },
        { target: 300, duration: "30s" },
        { target: 300, duration: "60s" },
        { target: 10, duration: "10s" },
        { target: 10, duration: "90s" },
      ],
    },
  },
  thresholds: {
    unexpected_status: ["count==0"],
    "http_req_duration{status:200}": ["p(99)<1000"],
    "http_req_duration{phase:recovered}": ["p(95)<300"],
    "rejected_503{phase:recovered}": ["count==0"],
  },
};

function phase(elapsedSeconds) {
  if (elapsedSeconds < 60) return "before";
  if (elapsedSeconds < SPIKE_END) return "spike";
  return elapsedSeconds < SPIKE_END + 30 ? "recovering" : "recovered";
}

export default function () {
  const tags = { phase: phase((Date.now() - exec.scenario.startTime) / 1000) };
  // 3,000 clients: at 300/s each sends 0.1 a second, so the rate limit is not what turns the spike away.
  const search = everydaySearch();
  const res = get(search.url, client(exec.scenario.iterationInTest % 3000), Object.assign(tags, search.tags));
  if (res.status !== 200 && res.status !== 429 && res.status !== 503) unexpected.add(1, tags);
  if (res.status === 503) rejected.add(1, tags);
}

export const handleSummary = summary("L3");
