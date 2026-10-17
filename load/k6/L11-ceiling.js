// L11: the traffic ceiling. L1's everyday mix at rising rates: a minute's warm-up at 50/s, then steps of 2 minutes
// from 50/s up by 25/s. A step meets L1's line when p95 < 300 ms, errors other than 429 < 0.5% and under 1% of
// travel-time searches fall back to straight-line order. The run stops at the first step that misses it, once that
// step has run a minute; the driver reports the last step before it as the sustained rate. With MIX=popular (L13c)
// the searches follow the popular mix instead, and NAME names the summary. With JUDGE_SECONDS a step is judged on its
// last JUDGE_SECONDS only, so an autoscaled service has the start of each step to add copies (the AWS ramp).
import exec from "k6/execution";
import { Counter, Rate } from "k6/metrics";
import { client, everydaySearch, get, popularSearch, summary, TREND_STATS } from "./lib.js";

const errors = new Rate("errors_not_429");
const fallbacks = new Rate("fallbacks");
const limited = new Counter("limited_429");
const rejected = new Counter("rejected_503");

const WARMUP = Number(__ENV.WARMUP_SECONDS || 60);
const STEP = Number(__ENV.STEP_SECONDS || 120);
const SETTLE = Number(__ENV.SETTLE_SECONDS || 60); // how long a step runs before a miss stops the test
const RAMP = 5; // seconds to move to the next step's rate; not counted in either step
const FROM = Math.max(RAMP, STEP - Number(__ENV.JUDGE_SECONDS || STEP)); // where a step's judged part starts
const FIRST = Number(__ENV.FIRST_RATE || 50);
const BY = Number(__ENV.STEP_BY || 25);
const LAST = Number(__ENV.LAST_RATE || 500);
const POPULAR_MIX = __ENV.MIX === "popular";
const NEW_SHARE = Number(__ENV.NEW_SHARE || 0.05);

const RATES = [];
for (let rate = FIRST; rate <= LAST; rate += BY) RATES.push(rate);

const stages = [{ target: FIRST, duration: `${WARMUP}s` }];
for (const rate of RATES) {
  stages.push({ target: rate, duration: `${RAMP}s` }, { target: rate, duration: `${STEP - RAMP}s` });
}

const thresholds = {
  limited_429: ["count==0"], // the per-client limit must not be what caps the rate
};
RATES.forEach((rate, i) => {
  const after = `${WARMUP + i * STEP + FROM - RAMP + SETTLE}s`;
  const stop = (threshold) => ({ threshold, abortOnFail: true, delayAbortEval: after });
  thresholds[`http_req_duration{step:${rate}}`] = [stop("p(95)<300")];
  thresholds[`errors_not_429{step:${rate}}`] = [stop("rate<0.005")];
  thresholds[`fallbacks{step:${rate}}`] = [stop("rate<0.01")];
  thresholds[`rejected_503{step:${rate}}`] = ["count>=0"]; // measured, not judged: 503s count as errors
});

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    ceiling: {
      executor: "ramping-arrival-rate", startRate: FIRST, timeUnit: "1s", preAllocatedVUs: 200, maxVUs: 2000,
      stages,
    },
  },
  thresholds,
};

function step(elapsedSeconds) {
  const t = elapsedSeconds - WARMUP;
  if (t < 0) return "none";
  const i = Math.floor(t / STEP);
  if (i >= RATES.length || t - i * STEP < FROM) return "none";
  return String(RATES[i]);
}

export default function () {
  const tags = { step: step((Date.now() - exec.scenario.startTime) / 1000) };
  // 3,000 clients, as L3: at 500/s each sends 0.17 a second, far under the per-client limit.
  const search = POPULAR_MIX ? popularSearch(exec.scenario.iterationInTest, NEW_SHARE) : everydaySearch();
  const res = get(search.url, client(exec.scenario.iterationInTest % 3000), Object.assign(tags, search.tags));
  errors.add(res.status !== 200 && res.status !== 429, tags);
  if (res.status === 200 && search.tags.mode !== "none") fallbacks.add(res.json("fallback") !== null, tags);
  if (res.status === 429) limited.add(1, tags);
  if (res.status === 503) rejected.add(1, tags);
}

export const handleSummary = summary(__ENV.NAME || "L11");
