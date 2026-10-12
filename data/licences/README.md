# Licence texts shipped with the data release

`tools/dev.sh release` copies these into each release folder, so the build needs no network. Retrieved
2026-10-11; re-fetch them when a licence or notice changes.

| File | What it is | Shipped when | From |
|---|---|---|---|
| `ODbL-1.0.txt` | The release's own licence, as `LICENSE` | always | SPDX license-list-data, `text/ODbL-1.0.txt` |
| `CDLA-Permissive-2.0.txt` | CDLA-P-2.0 §2.1: share "so long as the Data Recipient makes available the text of this agreement" | an Overture provider under CDLA is in the release | SPDX license-list-data |
| `Apache-2.0.txt` | Apache-2.0 §4(a): recipients get a copy of the licence | Foursquare (via Overture) is in the release | SPDX license-list-data |
| `Foursquare-NOTICE.txt` | Foursquare's NOTICE, word for word, as Apache-2.0 §4(d) and sources.yaml require | Foursquare is in the release | opensource.foursquare.com/places-notice-txt/ (the page's notice text) |

SPDX texts: `https://raw.githubusercontent.com/spdx/license-list-data/main/text/<id>.txt`.
