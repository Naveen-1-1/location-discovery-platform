"""Applies the observability config in this repo to the Grafana Cloud stack in .env.

Creates or updates, idempotently:
  - the "placemap" folder
  - every dashboard in infra/observability/grafana/dashboards/
  - the "email-placemap" contact point (address from ALERT_EMAIL; never stored in git)
  - the alert rules in infra/observability/alerts/, imported as Grafana-managed rules (the same files promtool tests)

Usage:  tools/dev.sh grafana [--test-email]
Needs GRAFANA_URL, GRAFANA_SA_TOKEN (service account, role Editor) and ALERT_EMAIL in .env.
Dashboards and the contact point are created editable in the UI (X-Disable-Provenance), so this script is the source
of truth but not a lock. Imported alert rules are read-only in the UI: change the rule files and run this again.
"""

import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FOLDER_UID = FOLDER_TITLE = "placemap"
CONTACT_POINT = "email-placemap"
METRICS_DS_UID = "grafanacloud-prom"


def load_env():
    """The Grafana settings, which tools/dev.sh loads from .env."""
    missing = [key for key in ('GRAFANA_URL', 'GRAFANA_SA_TOKEN', 'ALERT_EMAIL') if not os.environ.get(key)]
    if missing:
        sys.exit(f"{', '.join(missing)} not set: run this as tools/dev.sh grafana, with them in .env")
    return os.environ


ENV = load_env()


def api(method, path, body=None, ok=(200, 201, 202, 204), headers=None):
    """A JSON body, or a str sent as it is; headers replace the default ones of the same name."""
    headers = {"Content-Type": "application/json", "Accept": "application/json", "X-Disable-Provenance": "true",
               **(headers or {})}
    cmd = ["curl", "-sS", "-X", method, ENV["GRAFANA_URL"].rstrip("/") + path,
           "-H", f"Authorization: Bearer {ENV['GRAFANA_SA_TOKEN']}", "-w", "\n%{http_code}"]
    for name, value in headers.items():
        if value is not None:
            cmd += ["-H", f"{name}: {value}"]
    if body is not None:
        cmd += ["--data-binary", "@-"]
    data = body if isinstance(body, str) or body is None else json.dumps(body)
    out = subprocess.run(cmd, input=data, capture_output=True, text=True, check=True).stdout
    text, _, code = out.rpartition("\n")
    status = int(code)
    if status not in ok:
        sys.exit(f"{method} {path} -> HTTP {status}: {text[:300]}")
    return status, (json.loads(text) if text.strip() else None)


def ensure_folder():
    status, _ = api("GET", f"/api/folders/{FOLDER_UID}", ok=(200, 404))
    if status == 404:
        api("POST", "/api/folders", {"uid": FOLDER_UID, "title": FOLDER_TITLE})
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


def import_rule_groups():
    """Imports each Prometheus rule file into the placemap folder with Grafana's convert API, which replaces a group of
    the same name it imported before. Grafana evaluates them against the Grafana Cloud metrics and emails the contact
    point."""
    headers = {"Content-Type": "application/yaml", "X-Disable-Provenance": None,
               "X-Grafana-Alerting-Datasource-UID": METRICS_DS_UID,
               "X-Grafana-Alerting-Notification-Settings": json.dumps({"receiver": CONTACT_POINT})}
    for path in sorted((ROOT / "infra/observability/alerts").glob("*.rules.yaml")):
        text = path.read_text(encoding="utf-8")
        # A rule file is {groups: [...]}; the API takes {folder title: [...]}.
        body = re.sub(r"^groups:", f"{FOLDER_TITLE}:", text, count=1, flags=re.M)
        api("POST", "/api/convert/prometheus/config/v1/rules", body, headers=headers)
        groups = re.findall(r"^  - name: (\S+)", text, flags=re.M)
        alerts = re.findall(r"^      - alert: (\S+)", text, flags=re.M)
        print(f"rule group    {', '.join(groups)}: {', '.join(alerts)} (from {path.name})")


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
    import_rule_groups()
    if "--test-email" in sys.argv:
        send_test_email()
