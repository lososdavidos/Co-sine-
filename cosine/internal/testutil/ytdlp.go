package testutil

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeYtDlp answers from a fixtures file keyed by URL (or "scsearch" /
// "ytsearch"). A fixture with an "error" key fails the way yt-dlp does; one
// with "download_fails" fails its first that many downloads with
// "download_error". Each download appends its URL to a counter file, and
// one from a probe's description (--load-info-json) to the counter's
// ".from-info" file too.
const fakeYtDlp = `#!/usr/bin/env python3
import json, os, sys
fx = json.load(open(os.environ["FAKE_FIXTURES"]))
args = sys.argv[1:]
if "--load-info-json" in args:
    item = json.load(open(args[args.index("--load-info-json") + 1]))
    url = item["webpage_url"]
    with open(os.environ["FAKE_COUNTER"] + ".from-info", "a") as c: c.write(url + "\n")
else:
    url = args[args.index("--") + 1]
    key = url.split(":", 1)[0] if url.startswith(("scsearch", "ytsearch")) else url
    key = "".join(c for c in key if not c.isdigit()) if key.startswith(("scsearch", "ytsearch")) else key
    item = fx.get(key)
if item is None or "error" in (item or {}):
    sys.stderr.write("ERROR: [generic] x: " + ((item or {}).get("error") or "Unsupported URL: " + url) + "\n")
    sys.exit(1)
if "--dump-single-json" in args:
    print(json.dumps(item)); sys.exit(0)
if item.get("download_fails"):
    marker = os.environ["FAKE_COUNTER"] + ".fails"
    seen = open(marker).read().count(url + "\n") if os.path.exists(marker) else 0
    if seen < item["download_fails"]:
        with open(marker, "a") as m: m.write(url + "\n")
        sys.stderr.write("ERROR: [youtube] " + item["id"] + ": " + item["download_error"] + "\n")
        sys.exit(1)
out = args[args.index("--output") + 1].replace("%(id)s", item["id"]).replace("%(ext)s", item["ext"])
with open(out, "w") as f: f.write(item["body"])
print("COSINE-PROGRESS 50 100 NA"); print("COSINE-PROGRESS 100 100 NA")
print("COSINE-FILE " + out)
with open(os.environ["FAKE_COUNTER"], "a") as c: c.write(url + "\n")
`

// FakeYtDlp installs a fake yt-dlp serving fixtures and returns its path and
// the file counting downloads. Needs python3.
func FakeYtDlp(t testing.TB, fixtures map[string]any) (bin, counter string) {
	t.Helper()
	dir := t.TempDir()
	bin = filepath.Join(dir, "yt-dlp")
	if err := os.WriteFile(bin, []byte(fakeYtDlp), 0o755); err != nil {
		t.Fatal(err)
	}
	fx := filepath.Join(dir, "fixtures.json")
	b, _ := json.Marshal(fixtures)
	os.WriteFile(fx, b, 0o644)
	counter = filepath.Join(dir, "downloads.txt")
	t.Setenv("FAKE_FIXTURES", fx)
	t.Setenv("FAKE_COUNTER", counter)
	return bin, counter
}

// Downloads counts how many downloads the fake has served.
func Downloads(counter string) int {
	b, _ := os.ReadFile(counter)
	return strings.Count(string(b), "\n")
}
