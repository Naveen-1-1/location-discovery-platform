import type { NearbyPlace, PlaceDetails } from "./api";
import { HOURS_SOURCES } from "./categories";

/** The short line of badges under a result: open or closed, SNAP, inspection. Nothing for unknowns. */
export function FactsLine({ facts }: { facts: NearbyPlace["facts"] }) {
  const parts: { text: string; tone: "good" | "bad" | "plain" }[] = [];
  if (facts.openNow !== null) parts.push({ text: facts.openNow ? "Open now" : "Closed now", tone: facts.openNow ? "good" : "bad" });
  if (facts.acceptsSnap) parts.push({ text: "SNAP/EBT", tone: "plain" });
  if (facts.inspection === "passed") parts.push({ text: "Passed inspection", tone: "plain" });
  if (facts.inspection === "failed") parts.push({ text: "Failed last inspection", tone: "bad" });
  if (parts.length === 0) return null;
  return (
    <span className="facts">
      {parts.map((p, i) => (
        <span key={p.text} className={p.tone}>
          {i > 0 ? " · " : ""}
          {p.text}
        </span>
      ))}
    </span>
  );
}

/** Only http(s) links: websites come from open data, and a "javascript:" one must never become a link. */
export function safeWebsite(website: string | null): string | null {
  if (!website) return null;
  try {
    const url = new URL(website);
    return url.protocol === "https:" || url.protocol === "http:" ? url.href : null;
  } catch {
    return null;
  }
}

const DATE = new Intl.DateTimeFormat("en-US", { year: "numeric", month: "short", day: "numeric", timeZone: "America/New_York" });

/** What the popup adds once the place's details arrive. */
export function PlaceDetailsView({ details }: { details: PlaceDetails }) {
  const { hours, inspection, acceptsSnap, licensed, parkFeatures } = details.facts;
  return (
    <div className="details">
      {hours && (
        <div>
          <span className={hours.openNow ? "good" : "bad"}>{hours.openNow ? "Open now" : "Closed now"}</span>
          {" · "}
          {hours.text}
          <div className="muted small">{HOURS_SOURCES[hours.source]}; holidays not included</div>
        </div>
      )}
      {inspection && (
        <div>
          {inspection.result === "passed" ? "Passed" : inspection.result === "failed" ? "Failed" : "Had"} its latest
          health inspection{inspection.at ? ` (${DATE.format(new Date(inspection.at))})` : ""}
          {inspection.failedChecks12m ? `; ${inspection.failedChecks12m} failed checks in the last 12 months` : ""}
        </div>
      )}
      {(acceptsSnap || licensed) && (
        <div>{[acceptsSnap ? "Accepts SNAP/EBT" : null, licensed ? "Holds an active licence" : null].filter(Boolean).join(" · ")}</div>
      )}
      {parkFeatures.length > 0 && <div>Has: {parkFeatures.map((f) => (f.count > 1 ? `${f.name} ×${f.count}` : f.name)).join(", ")}</div>}
      {details.phone && <div>{details.phone}</div>}
      {safeWebsite(details.website) && (
        <div>
          <a href={safeWebsite(details.website)!} target="_blank" rel="noreferrer noopener">
            Website
          </a>
        </div>
      )}
    </div>
  );
}
