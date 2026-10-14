// L4: one client sends 100 requests a second while 20 ordinary clients send 2 a second each, for 3 minutes.
// Pass: the abuser gets no more through than its limit allows: 10 a second plus the burst of 20 (over 3 minutes
// 1,820 of 18,000, so about 90% answer 429), with 10 requests to spare for the edges of the run. The ordinary clients
// get no 429 and their p95 is within 20% of L1's (the driver compares).
import exec from "k6/execution";
import { Counter } from "k6/metrics";
import { client, everydaySearch, get, summary, TREND_STATS } from "./lib.js";

const limited = new Counter("limited_429");
const through = new Counter("let_through");
const SECONDS = Number(__ENV.SECONDS || 180);
const ABUSER = 65535; // 198.18.255.255, not one of the ordinary clients

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    abuser: { executor: "constant-arrival-rate", rate: 100, timeUnit: "1s", duration: `${SECONDS}s`,
              preAllocatedVUs: 50, maxVUs: 300, exec: "abuser", tags: { who: "abuser" } },
    ordinary: { executor: "constant-arrival-rate", rate: 40, timeUnit: "1s", duration: `${SECONDS}s`,
                preAllocatedVUs: 30, maxVUs: 300, exec: "ordinary", tags: { who: "ordinary" } },
  },
  thresholds: {
    "limited_429{who:ordinary}": ["count==0"],
    "let_through{who:abuser}": [`count<=${10 * SECONDS + 20 + 10}`],
    "limited_429{who:abuser}": ["count>=0"], // listed so the summary has it
    "http_req_duration{who:ordinary}": ["p(95)<1000"], // listed so the summary has it; the driver compares with L1
  },
};

function send(n) {
  const search = everydaySearch();
  const res = get(search.url, client(n), search.tags);
  if (res.status === 429) limited.add(1);
  else through.add(1);
}

export function abuser() {
  send(ABUSER);
}

export function ordinary() {
  send(exec.scenario.iterationInTest % 20);
}

export const handleSummary = summary("L4");
