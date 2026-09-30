#!/usr/bin/env bash
# Runs Sine's real client code against a real Cosine: builds and starts the
# server in a scratch directory, sets it up, seeds a small library through
# the Inbox, then runs sine/core's LiveCosineTest against it.
#
#   scripts/contract-test.sh
#
# Needs: go, python3, sqlite3, curl, a JDK. yt-dlp is optional (the link
# fetch part of the test needs it). GRADLE overrides how the test is run.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PORT="${PORT:-4610}"
FILE_PORT="${FILE_PORT:-4611}"
USER_NAME="contract"
USER_PASS="contract-pass"
pids=()
cleanup() {
	for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
	rm -rf "$WORK"
}
trap cleanup EXIT

echo "==> Building Cosine"
(cd "$ROOT/cosine" && CGO_ENABLED=0 go build -o "$WORK/cosine" ./cmd/cosine)

echo "==> Starting Cosine on :$PORT"
"$WORK/cosine" --data "$WORK/data" --listen "127.0.0.1:$PORT" >"$WORK/cosine.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 50); do curl -fsS "127.0.0.1:$PORT/cosine/v1/capabilities" >/dev/null 2>&1 && break; sleep 0.1; done

curl -fsS -o /dev/null -X POST \
	-d "store=$WORK/store&inbox=$WORK/inbox&username=$USER_NAME&password=$USER_PASS&password2=$USER_PASS" \
	"127.0.0.1:$PORT/setup"

echo "==> Seeding the library through the Inbox"
python3 - "$WORK" <<'PY'
import os, struct, sys
work = sys.argv[1]
def frame(fid, data): return fid.encode() + struct.pack(">I", len(data)) + b"\0\0" + data
def text(fid, v): return frame(fid, b"\0" + v.encode())
def mp3(path, title, n, body):
    frames = text("TIT2", title) + text("TPE1", "Contract Fixture") + text("TALB", "Tides EP") + text("TRCK", str(n))
    frames += frame("APIC", b"\0image/jpeg\0\x03\0" + b"\xff\xd8\xff\xe0fixture-cover")
    size = len(frames)
    header = b"ID3\x03\x00\x00" + bytes([(size >> 21) & 127, (size >> 14) & 127, (size >> 7) & 127, size & 127])
    with open(path, "wb") as f:
        f.write(header + frames + b"\xff\xfb\x90\x00" + body)
os.makedirs(f"{work}/staging", exist_ok=True)
mp3(f"{work}/staging/tides.mp3", "Tides", 1, os.urandom(40000))
mp3(f"{work}/staging/surge.mp3", "Surge", 2, os.urandom(30000))
os.makedirs(f"{work}/files", exist_ok=True)
with open(f"{work}/files/fetched.mp3", "wb") as f:
    f.write(b"\xff\xfb\x90\x00" + os.urandom(20000))
PY
# Moved in whole, the way a finished copy lands.
mv "$WORK/staging/tides.mp3" "$WORK/staging/surge.mp3" "$WORK/inbox/"
for _ in $(seq 1 100); do
	[[ "$(sqlite3 "$WORK/data/cosine.db" "SELECT COUNT(*) FROM objects")" == 2 ]] && break
	sleep 0.2
done
[[ "$(sqlite3 "$WORK/data/cosine.db" "SELECT COUNT(*) FROM objects")" == 2 ]] || {
	cat "$WORK/cosine.log"
	echo "Inbox ingest did not finish"
	exit 1
}
# Cosine can't create playlists yet; seed one directly.
sqlite3 "$WORK/data/cosine.db" <<'SQL'
.timeout 5000
INSERT INTO playlists(id, owner_id, name, created_at, updated_at) VALUES(1, 1, 'Fixture list', 0, 0);
INSERT INTO playlist_entries(playlist_id, position, track_id) SELECT 1, 0, id FROM tracks WHERE title = 'Surge';
INSERT INTO playlist_entries(playlist_id, position, track_id) SELECT 1, 1, id FROM tracks WHERE title = 'Tides';
SQL

# Started directly (not in a subshell) so cleanup kills the server itself.
python3 -m http.server "$FILE_PORT" --bind 127.0.0.1 --directory "$WORK/files" >/dev/null 2>&1 &
pids+=($!)
for _ in $(seq 1 50); do curl -fsS -o /dev/null "127.0.0.1:$FILE_PORT/fetched.mp3" 2>/dev/null && break; sleep 0.1; done

echo "==> Running Sine's client against it"
export COSINE_URL="http://127.0.0.1:$PORT" COSINE_USER="$USER_NAME" COSINE_PASS="$USER_PASS"
export FETCH_URL="http://127.0.0.1:$FILE_PORT/fetched.mp3"
if ! (cd "$ROOT/sine" && ${GRADLE:-./gradlew} :core:test --tests 'app.sine.core.LiveCosineTest' --rerun); then
	echo "---- cosine log ----"
	cat "$WORK/cosine.log"
	exit 1
fi
echo "==> Sine and Cosine agree."
