"""Applies the observability config in this repo to the Grafana Cloud stack in .env (ADR 0033, 0035).

Creates or updates, idempotently:
  - the "placemap" folder
  - every dashboard in infra/observability/grafana/dashboards/
  - the "email-placemap" contact point (address from ALERT_EMAIL; never stored in git)
  - the alert rule groups in infra/observability/grafana/alerting/

Usage:  python3 -I tools/grafana-apply.py [--test-email]
Needs GRAFANA_URL, GRAFANA_SA_TOKEN (service account, role Editor) and ALERT_EMAIL in .env.
Resources are created editable in the UI (X-Disable-Provenance), so this script is the source of truth but not a lock.
"""

import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FOLDER_UID = "placemap"
CONTACT_POINT = "email-placemap"
METRICS_DS_UID = "grafanacloud-prom"


def load_env():
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.split("  #")[0].strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        env[key] = value
    for key in ("GRAFANA_URL", "GRAFANA_SA_TOKEN", "ALERT_EMAIL"):
        if not env.get(key):
            sys.exit(f"{key} is empty in .env")
    return env


ENV = load_env()


def api(method, path, body=None, ok=(200, 201, 202, 204)):
    cmd = ["curl", "-sS", "-X", method, ENV["GRAFANA_URL"].rstrip("/") + path,
           "-H", f"Authorization: Bearer {ENV['GRAFANA_SA_TOKEN']}",
           "-H", "Content-Type: application/json", "-H", "Accept: application/json",
           "-H", "X-Disable-Provenance: true",
           "-w", "\n%{http_code}"]
    if body is not None:
        cmd += ["--data-binary", "@-"]
    out = subprocess.run(cmd, input=json.dumps(body) if body is not None else None,
                         capture_output=True, text=True, check=True).stdout
    text, _, code = out.rpartition("\n")
    status = int(code)
    if status not in ok:
        sys.exit(f"{method} {path} -> HTTP {status}: {text[:300]}")
    return status, (json.loads(text) if text.strip() else None)


def ensure_folder():
    status, _ = api("GET", f"/api/folders/{FOLDER_UID}", ok=(200, 404))
    if status == 404:
        api("POST", "/api/folders", {"uid": FOLDER_UID, "title": "placemap"})
    print(f"folder        {FOLDER_UID}")


def apply_dashboards():
    for path in sorted((ROOT / "infra/observability/grafana/dashboards").glob("*.json")):
        dashboard = json.loads(path.read_text(encoding="utf-8"))
        for var in dashboard.get("templating", {}).get("list", []):
            if var.get("name") == "datasource":
                var["current"] = {"value": METRICS_DS_UID}
        _, res = api("POST", "/api/dashboards/db", {"dashboard": dashboard, "folderUid": FOLDER_UID,
                                                    "overwrite": True, "message": f"tools/grafana-apply.py ({path.name})"})
        print(f"dashboard     {dashboard['title']}  ->  {ENV['GRAFANA_URL'].rstrip('/')}{res['url']}")


def ensure_contact_point():
    _, existing = api("GET", "/api/v1/provisioning/contact-points")
    body = {"name": CONTACT_POINT, "type": "email",
            "settings": {"addresses": ENV["ALERT_EMAIL"], "singleEmail": True}, "disableResolveMessage": False}
    current = next((c for c in existing if c.get("name") == CONTACT_POINT), None)
    if current:
        api("PUT", f"/api/v1/provisioning/contact-points/{current['uid']}", {**body, "uid": current["uid"]})
    else:
        api("POST", "/api/v1/provisioning/contact-points", body)
    print(f"contact point {CONTACT_POINT} (email)")


def apply_rule_groups():
    for path in sorted((ROOT / "infra/observability/grafana/alerting").glob("*.rules.json")):
        group = json.loads(path.read_text(encoding="utf-8"))
        for rule in group["rules"]:
            rule.update(folderUID=FOLDER_UID, ruleGroup=group["title"])
        api("PUT", f"/api/v1/provisioning/folder/{FOLDER_UID}/rule-groups/{group['title']}", group)
        print(f"rule group    {group['title']}: {', '.join(r['title'] for r in group['rules'])} (every {group['interval']}s)")


def send_test_email():
    # Grafana 13 removed /api/alertmanager/.../receivers/test; receivers now live under the app-platform API.
    _, settings = api("GET", "/api/frontend/settings")
    base = f"/apis/notifications.alerting.grafana.app/v1beta1/namespaces/{settings['namespace']}/receivers"
    _, receivers = api("GET", base)
    receiver = next(r for r in receivers["items"] if r["spec"]["title"] == CONTACT_POINT)
    integration = receiver["spec"]["integrations"][0]
    api("POST", f"{base}/{receiver['metadata']['name']}/test", {"integration": integration})
    print(f"test email    sent to the {CONTACT_POINT} contact point")


if __name__ == "__main__":
    ensure_folder()
    apply_dashboards()
    ensure_contact_point()
    apply_rule_groups()
    if "--test-email" in sys.argv:
        send_test_email()
