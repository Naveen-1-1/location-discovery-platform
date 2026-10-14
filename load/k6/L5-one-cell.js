// L5: 200 users in the same map cell search again and again for 2 minutes, by foot, bike and car.
// Pass: at most one Valhalla call per search (cell and mode) while its result is shared; the driver counts the api's
// calls to Valhalla in Prometheus. Every answer must have Valhalla's travel times.
import { check, sleep } from "k6";
import exec from "k6/execution";
import { Counter } from "k6/metrics";
import { client, get, L5_ORIGIN, nearbyUrl, ORIGINS, summary, TREND_STATS } from "./lib.js";

const cells = new Counter("other_cell");
const MODES = ["walk", "bike", "drive"];
const EXPECTED_CELL = __ENV.EXPECTED_CELL || "";

export const options = {
  summaryTrendStats: TREND_STATS,
  scenarios: {
    crowd: { executor: "constant-vus", vus: 200, duration: __ENV.DURATION || "2m" },
  },
  thresholds: {
    checks: ["rate==1"],
    other_cell: ["count==0"],
  },
};

export default function () {
  const vu = exec.vu.idInTest;
  const [lat, lon] = ORIGINS[L5_ORIGIN];
  // Within about 3 m of the square's centre, so every user lands in the same cell.
  const origin = [lat + (Math.random() - 0.5) * 0.00005, lon + (Math.random() - 0.5) * 0.00007];
  const mode = MODES[vu % MODES.length];
  const res = get(nearbyUrl(origin, mode, null), client(vu), { mode });
  check(res, {
    "200 with Valhalla's travel times": (r) => r.status === 200 && r.json("travelTimeSource") === "valhalla",
  });
  if (res.status === 200 && EXPECTED_CELL && res.json("snappedCell") !== EXPECTED_CELL) cells.add(1);
  sleep(1);
}

export const handleSummary = summary("L5");
