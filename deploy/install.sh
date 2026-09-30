#!/usr/bin/env bash
# Cosine installer for Debian and Ubuntu: Proxmox LXC containers (privileged
# or unprivileged), VMs and bare metal.
#
#   bash -c "$(curl -fsSL https://github.com/lososdavidos/Co-sine-/releases/latest/download/install.sh)"
#
# Run it again to upgrade: Cosine and its dependencies are brought up to date,
# and your settings, data and music are left alone. --help lists the options.

set -Eeuo pipefail

REPO="lososdavidos/Co-sine-"
SERVICE_USER="cosine"
PREFIX="/opt/cosine"
CONF_DIR="/etc/cosine"
ENV_FILE="$CONF_DIR/cosine.env"
BIN_DIR="/usr/local/bin"
UNIT_DIR="/etc/systemd/system"

# Defaults, overridable by flags.
VERSION="latest"
LOCAL_BINARY=""
DATA_DIR="/var/lib/cosine"
MUSIC_DIR="/srv/music"
LISTEN=":4534"
WANT_UID=""
WANT_GID=""
WITH_DENO=1
WITH_BACKUP=1
WITH_FINGERPRINT=0
START=1
UNINSTALL=0
PURGE=0

usage() {
	cat <<EOF
Install or upgrade Cosine and everything it needs.

Usage: install.sh [options]

  --version vX.Y.Z     Install a specific Cosine release (default: latest)
  --binary PATH        Install this cosine binary instead of downloading one
  --listen ADDR        Address to listen on (default: $LISTEN)
  --data-dir DIR       Database, key and artwork cache (default: $DATA_DIR)
  --music-dir DIR      Parent of the default Store and Inbox (default: $MUSIC_DIR)
  --uid N, --gid N     IDs for the '$SERVICE_USER' user. Match these to the owner
                       of bind-mounted music folders (see "Unprivileged" below).
  --no-deno            Skip Deno. yt-dlp needs a JavaScript runtime for YouTube.
  --no-backup          Skip the nightly database backup.
  --with-fingerprinting
                       Also install Chromaprint (fpcalc) for audio
                       fingerprinting. Nothing in Cosine uses it yet.
  --no-start           Install everything but don't start or enable services.
  --uninstall          Remove Cosine's program and services. Data and music stay.
  --purge              With --uninstall, also delete $DATA_DIR and $CONF_DIR.
                       Music folders are never deleted.
  -h, --help           Show this help.

Unprivileged containers: the container's root is not the host's root, so
folders bind-mounted from the host appear owned by 'nobody' unless their
host-side owner is in the container's ID range. With the default mapping,
container UID N is host UID 100000+N. Either chown the host folder to
100000+<uid of '$SERVICE_USER'>, or pass --uid/--gid to match its owner.
EOF
}

# ---------------------------------------------------------------- output

if [[ -t 1 ]]; then
	BOLD=$'\e[1m' DIM=$'\e[2m' RED=$'\e[31m' YELLOW=$'\e[33m' GREEN=$'\e[32m' RESET=$'\e[0m'
else
	BOLD="" DIM="" RED="" YELLOW="" GREEN="" RESET=""
fi
step() { printf '%s==>%s %s\n' "$BOLD" "$RESET" "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '%s!!  %s%s\n' "$YELLOW" "$*" "$RESET" >&2; }
die() {
	printf '%sxx  %s%s\n' "$RED" "$*" "$RESET" >&2
	exit 1
}
trap 'die "Failed at line $LINENO: $BASH_COMMAND"' ERR

# ---------------------------------------------------------------- arguments

while [[ $# -gt 0 ]]; do
	case "$1" in
	--version) VERSION="${2:?--version needs a value}"; shift ;;
	--binary) LOCAL_BINARY="${2:?--binary needs a path}"; shift ;;
	--listen) LISTEN="${2:?--listen needs a value}"; shift ;;
	--data-dir) DATA_DIR="${2:?--data-dir needs a path}"; shift ;;
	--music-dir) MUSIC_DIR="${2:?--music-dir needs a path}"; shift ;;
	--uid) WANT_UID="${2:?--uid needs a number}"; shift ;;
	--gid) WANT_GID="${2:?--gid needs a number}"; shift ;;
	--no-deno) WITH_DENO=0 ;;
	--no-backup) WITH_BACKUP=0 ;;
	--with-fingerprinting) WITH_FINGERPRINT=1 ;;
	--no-start) START=0 ;;
	--uninstall) UNINSTALL=1 ;;
	--purge) PURGE=1 ;;
	-h | --help) usage; exit 0 ;;
	*) die "Unknown option: $1 (see --help)" ;;
	esac
	shift
done

[[ -z "$WANT_UID" || "$WANT_UID" =~ ^[0-9]+$ ]] || die "--uid must be a number"
[[ -z "$WANT_GID" || "$WANT_GID" =~ ^[0-9]+$ ]] || die "--gid must be a number"
[[ "$VERSION" == "latest" || "$VERSION" == v* ]] || VERSION="v$VERSION"

# ---------------------------------------------------------------- environment

[[ $EUID -eq 0 ]] || die "Run as root (inside the container: 'pct enter <id>', then run this)."

# shellcheck source=/dev/null
. /etc/os-release
case " ${ID:-} ${ID_LIKE:-} " in
*" debian "* | *" ubuntu "*) ;;
*) die "Only Debian and Ubuntu are supported (found ${PRETTY_NAME:-unknown})." ;;
esac

case "$(uname -m)" in
x86_64) ARCH="amd64" YTDLP_ASSET="yt-dlp_linux" DENO_TRIPLE="x86_64-unknown-linux-gnu" ;;
aarch64 | arm64) ARCH="arm64" YTDLP_ASSET="yt-dlp_linux_aarch64" DENO_TRIPLE="aarch64-unknown-linux-gnu" ;;
*) die "Unsupported CPU architecture: $(uname -m). Cosine ships for amd64 and arm64." ;;
esac

# Where are we? Only used to give better advice.
CONTAINER="none"
if command -v systemd-detect-virt >/dev/null && systemd-detect-virt -cq; then
	CONTAINER="$(systemd-detect-virt -c)"
elif grep -qa 'container=lxc' /proc/1/environ 2>/dev/null; then
	CONTAINER="lxc"
fi
UNPRIVILEGED=0
if [[ "$CONTAINER" != "none" ]] && ! grep -Eq '^\s*0\s+0\s+4294967295\s*$' /proc/self/uid_map; then
	UNPRIVILEGED=1
fi
# systemd may be installed but not running (an image build, a chroot).
HAVE_SYSTEMD=0
[[ -d /run/systemd/system ]] && HAVE_SYSTEMD=1

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------- helpers

download() { # url dest
	curl -fsSL --retry 3 --retry-delay 2 --proto '=https' --tlsv1.2 -o "$2" "$1" ||
		die "Download failed: $1"
}

# verify FILE SUMS_FILE NAME: checks FILE against NAME's line in a sha256 list.
verify() {
	local want got
	want="$(awk -v n="$3" '{ f = $2; sub(/^\*/, "", f) } f == n { print $1; exit }' "$2")"
	[[ -n "$want" ]] || die "No checksum for $3 in the published checksum list."
	got="$(sha256sum "$1" | awk '{ print $1 }')"
	[[ "$want" == "$got" ]] || die "Checksum mismatch for $3: refusing to install it."
}

systemd_run() { # systemctl args, only when systemd is running
	if [[ $HAVE_SYSTEMD -eq 1 ]]; then systemctl "$@"; fi
}

# ---------------------------------------------------------------- uninstall

if [[ $UNINSTALL -eq 1 ]]; then
	step "Removing Cosine"
	for unit in cosine.service yt-dlp-update.timer cosine-backup.timer; do
		systemd_run disable --now "$unit" 2>/dev/null || true
	done
	rm -f "$UNIT_DIR"/cosine.service "$UNIT_DIR"/yt-dlp-update.{service,timer} "$UNIT_DIR"/cosine-backup.{service,timer}
	rm -rf "$UNIT_DIR/cosine.service.d"
	systemd_run daemon-reload
	rm -rf "$PREFIX"
	info "yt-dlp, Deno and ffmpeg were left installed; other software may use them."
	if [[ $PURGE -eq 1 ]]; then
		rm -rf "$CONF_DIR" "$DATA_DIR"
		info "Deleted $CONF_DIR and $DATA_DIR."
	else
		info "Kept $CONF_DIR and $DATA_DIR. Add --purge to delete them."
	fi
	info "Music folders were not touched."
	exit 0
fi

# ---------------------------------------------------------------- components
#
# Each component installs or upgrades one dependency and is safe to rerun.
# Future metadata sources slot in here: MusicBrainz, Discogs and Bandcamp
# lookups are HTTP calls made by Cosine itself and need nothing installed;
# audio fingerprinting (AcoustID) needs Chromaprint's fpcalc, below.

install_packages() {
	step "Installing system packages"
	local packages=(ca-certificates curl unzip ffmpeg sqlite3 tzdata)
	[[ $WITH_FINGERPRINT -eq 1 ]] && packages+=(libchromaprint-tools)
	export DEBIAN_FRONTEND=noninteractive
	apt-get update -qq
	apt-get install -y -qq --no-install-recommends "${packages[@]}" >/dev/null
	info "ffmpeg (for ffprobe), sqlite3 (for backups)$([[ $WITH_FINGERPRINT -eq 1 ]] && echo ", Chromaprint")"
}

# yt-dlp: the official standalone build. Distribution packages lag the sites
# they scrape and stop working; this one updates itself daily (timer below).
install_ytdlp() {
	step "Installing yt-dlp"
	local base="https://github.com/yt-dlp/yt-dlp/releases/latest/download"
	download "$base/$YTDLP_ASSET" "$TMP/$YTDLP_ASSET"
	download "$base/SHA2-256SUMS" "$TMP/yt-dlp.sums"
	verify "$TMP/$YTDLP_ASSET" "$TMP/yt-dlp.sums" "$YTDLP_ASSET"
	install -m 0755 "$TMP/$YTDLP_ASSET" "$BIN_DIR/yt-dlp"
	info "yt-dlp $("$BIN_DIR/yt-dlp" --version)"
}

# Deno: the JavaScript runtime yt-dlp uses for YouTube. Without one, YouTube
# downloads fail or come back degraded; SoundCloud and Bandcamp don't need it.
install_deno() {
	[[ $WITH_DENO -eq 1 ]] || return 0
	step "Installing Deno (JavaScript runtime for yt-dlp's YouTube support)"
	local zip="deno-$DENO_TRIPLE.zip" base="https://github.com/denoland/deno/releases/latest/download"
	download "$base/$zip" "$TMP/$zip"
	download "$base/$zip.sha256sum" "$TMP/deno.sums"
	verify "$TMP/$zip" "$TMP/deno.sums" "$zip"
	unzip -oq "$TMP/$zip" -d "$TMP/deno"
	install -m 0755 "$TMP/deno/deno" "$BIN_DIR/deno"
	info "$("$BIN_DIR/deno" --version | head -1)"
}

install_cosine() {
	step "Installing Cosine"
	local bin="$TMP/cosine"
	if [[ -n "$LOCAL_BINARY" ]]; then
		[[ -f "$LOCAL_BINARY" ]] || die "No such file: $LOCAL_BINARY"
		cp "$LOCAL_BINARY" "$bin"
		info "From $LOCAL_BINARY"
	else
		local base="https://github.com/$REPO/releases/latest/download"
		[[ "$VERSION" != "latest" ]] && base="https://github.com/$REPO/releases/download/$VERSION"
		local asset="cosine-linux-$ARCH"
		curl -fsSL --retry 3 --proto '=https' -o "$bin" "$base/$asset" ||
			die "Could not download $asset ($VERSION). Has a release been published? See https://github.com/$REPO/releases, or pass --binary."
		download "$base/SHA256SUMS" "$TMP/cosine.sums"
		verify "$bin" "$TMP/cosine.sums" "$asset"
	fi
	chmod 0755 "$bin"
	"$bin" -version >/dev/null 2>&1 || die "That cosine binary does not run on this machine."
	install -d -m 0755 "$PREFIX/bin"
	install -m 0755 "$bin" "$PREFIX/bin/cosine"
	info "Cosine $("$PREFIX/bin/cosine" -version)"
}

# ---------------------------------------------------------------- user and folders

create_user() {
	step "Creating the '$SERVICE_USER' service account"
	if [[ -n "$WANT_GID" ]] && ! getent group "$SERVICE_USER" >/dev/null; then
		groupadd --system --gid "$WANT_GID" "$SERVICE_USER"
	elif ! getent group "$SERVICE_USER" >/dev/null; then
		groupadd --system "$SERVICE_USER"
	fi
	if ! id "$SERVICE_USER" >/dev/null 2>&1; then
		local uid_args=()
		[[ -n "$WANT_UID" ]] && uid_args=(--uid "$WANT_UID")
		useradd --system --gid "$SERVICE_USER" "${uid_args[@]}" \
			--home-dir "$DATA_DIR" --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
	elif [[ -n "$WANT_UID" && "$(id -u "$SERVICE_USER")" != "$WANT_UID" ]]; then
		warn "'$SERVICE_USER' already exists with UID $(id -u "$SERVICE_USER"), not $WANT_UID. Leaving it as is."
	fi
	info "UID $(id -u "$SERVICE_USER"), GID $(id -g "$SERVICE_USER")"
}

# prepare_dir DIR: create it for the service user. An existing folder is
# never chowned recursively: it may be a bind mount holding a whole library.
prepare_dir() {
	local dir="$1"
	if [[ ! -e "$dir" ]]; then
		install -d -m 0755 -o "$SERVICE_USER" -g "$SERVICE_USER" "$dir"
		return
	fi
	if [[ -z "$(ls -A "$dir" 2>/dev/null)" ]]; then
		chown "$SERVICE_USER:$SERVICE_USER" "$dir" 2>/dev/null || true
	fi
	if ! runuser -u "$SERVICE_USER" -- test -w "$dir"; then
		NOT_WRITABLE+=("$dir")
	fi
}

prepare_folders() {
	step "Preparing folders"
	NOT_WRITABLE=()
	install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$DATA_DIR"
	# A previous version may have left files owned by someone else.
	chown -R "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR"
	install -d -m 0755 "$MUSIC_DIR"
	prepare_dir "$MUSIC_DIR/store"
	prepare_dir "$MUSIC_DIR/inbox"
	info "Data:  $DATA_DIR"
	info "Store: $MUSIC_DIR/store  (suggested; the path is chosen in the dashboard)"
	info "Inbox: $MUSIC_DIR/inbox"
}

write_config() {
	step "Writing $ENV_FILE"
	install -d -m 0755 "$CONF_DIR"
	if [[ -f "$ENV_FILE" ]]; then
		info "Kept your existing settings. (Flags only apply on a first install; edit the file to change them.)"
		return
	fi
	cat >"$ENV_FILE" <<EOF
# Cosine settings. Restart after editing: systemctl restart cosine
COSINE_DATA=$DATA_DIR
COSINE_LISTEN=$LISTEN
COSINE_YTDLP=$BIN_DIR/yt-dlp
# yt-dlp finds deno on PATH for YouTube support.
PATH=$BIN_DIR:/usr/bin:/bin
EOF
	chmod 0644 "$ENV_FILE"
}

# ---------------------------------------------------------------- services

write_units() {
	step "Installing services"
	cat >"$UNIT_DIR/cosine.service" <<EOF
[Unit]
Description=Cosine music server
Documentation=https://github.com/$REPO
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
EnvironmentFile=$ENV_FILE
ExecStart=$PREFIX/bin/cosine
Restart=on-failure
RestartSec=5
# Scanning must not make the box unusable (G6).
Nice=5
IOSchedulingClass=best-effort
IOSchedulingPriority=6
UMask=0022

[Install]
WantedBy=multi-user.target
EOF

	# Sandboxing lives in a drop-in: some unprivileged containers without
	# nesting can't create the namespaces it needs. If the service then fails
	# to start, the drop-in is removed and it starts without (see start_services).
	install -d "$UNIT_DIR/cosine.service.d"
	cat >"$UNIT_DIR/cosine.service.d/hardening.conf" <<EOF
[Service]
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
ProtectControlGroups=yes
ProtectKernelModules=yes
ProtectKernelTunables=yes
RestrictSUIDSGID=yes
LockPersonality=yes
EOF

	cat >"$UNIT_DIR/yt-dlp-update.service" <<EOF
[Unit]
Description=Update yt-dlp (sites change; yt-dlp follows them)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=$BIN_DIR/yt-dlp --update-to stable
EOF
	cat >"$UNIT_DIR/yt-dlp-update.timer" <<EOF
[Unit]
Description=Update yt-dlp daily

[Timer]
OnCalendar=daily
RandomizedDelaySec=2h
Persistent=true

[Install]
WantedBy=timers.target
EOF

	if [[ $WITH_BACKUP -eq 1 ]]; then
		install -d -m 0755 "$PREFIX/libexec"
		cat >"$PREFIX/libexec/backup.sh" <<EOF
#!/bin/sh
# Nightly copy of everything only the database knows (§5.1): the Store can be
# re-fetched, this can't. SQLite's online backup is safe while Cosine runs.
set -eu
data="$DATA_DIR"
# Nothing to back up before setup; sqlite3 would create an empty database.
[ -s "\$data/cosine.db" ] || exit 0
dest="\$data/backups/\$(date +%Y-%m-%d)"
mkdir -p "\$dest"
sqlite3 "\$data/cosine.db" ".backup '\$dest/cosine.db'"
cp -p "\$data/secret.key" "\$dest/secret.key"
# Keep the newest seven.
ls -1d "\$data"/backups/*/ 2>/dev/null | sort | head -n -7 | xargs -r rm -rf
EOF
		chmod 0755 "$PREFIX/libexec/backup.sh"
		cat >"$UNIT_DIR/cosine-backup.service" <<EOF
[Unit]
Description=Back up Cosine's database
ConditionPathExists=$DATA_DIR/cosine.db

[Service]
Type=oneshot
User=$SERVICE_USER
Group=$SERVICE_USER
ExecStart=$PREFIX/libexec/backup.sh
EOF
		cat >"$UNIT_DIR/cosine-backup.timer" <<EOF
[Unit]
Description=Back up Cosine's database nightly

[Timer]
OnCalendar=*-*-* 03:30
RandomizedDelaySec=30m
Persistent=true

[Install]
WantedBy=timers.target
EOF
	fi
	systemd_run daemon-reload
}

start_services() {
	if [[ $HAVE_SYSTEMD -eq 0 ]]; then
		warn "systemd isn't running here, so nothing was started. Run $PREFIX/bin/cosine with the settings in $ENV_FILE."
		return
	fi
	if [[ $START -eq 0 ]]; then
		info "Not starting services (--no-start)."
		return
	fi
	step "Starting Cosine"
	systemctl enable --now yt-dlp-update.timer >/dev/null
	[[ $WITH_BACKUP -eq 1 ]] && systemctl enable --now cosine-backup.timer >/dev/null
	systemctl enable cosine.service >/dev/null
	systemctl restart cosine.service
	sleep 2
	if ! systemctl is-active --quiet cosine.service; then
		if systemctl status cosine.service 2>/dev/null | grep -q 'NAMESPACE'; then
			warn "This container can't sandbox services (enable 'nesting' in its Options to allow it). Starting without the sandbox."
			rm -f "$UNIT_DIR/cosine.service.d/hardening.conf"
			systemctl daemon-reload
			systemctl restart cosine.service
			sleep 2
		fi
	fi
	systemctl is-active --quiet cosine.service ||
		die "Cosine did not start. See: journalctl -u cosine -n 50"
	info "Running."
}

# ---------------------------------------------------------------- run

step "Cosine installer ($PRETTY_NAME, $ARCH, $(
	case "$CONTAINER" in
	none) echo "not a container" ;;
	*) [[ $UNPRIVILEGED -eq 1 ]] && echo "unprivileged $CONTAINER" || echo "privileged $CONTAINER" ;;
	esac
))"

install_packages
install_ytdlp
install_deno
install_cosine
create_user
prepare_folders
write_config
write_units
start_services

# ---------------------------------------------------------------- summary

port="${LISTEN##*:}"
address="$(hostname -I 2>/dev/null | awk '{ print $1 }')"
echo
printf '%sCosine is installed.%s\n' "$GREEN$BOLD" "$RESET"
echo "  Dashboard:   http://${address:-<this-host>}:$port/  (first visit runs setup)"
echo "  Sine / apps: http://${address:-<this-host>}:$port"
echo "  Settings:    $ENV_FILE"
echo "  Logs:        journalctl -u cosine -f"
[[ $WITH_BACKUP -eq 1 ]] && echo "  Backups:     $DATA_DIR/backups (nightly, newest 7). Copy them off this machine too."
echo "  Upgrade:     run this installer again"

if [[ ${#NOT_WRITABLE[@]} -gt 0 ]]; then
	echo
	warn "The '$SERVICE_USER' user can't write to: ${NOT_WRITABLE[*]}"
	if [[ $UNPRIVILEGED -eq 1 ]]; then
		cat >&2 <<EOF
    This is an unprivileged container, so a folder bind-mounted from the host
    must be owned by the mapped ID. On the Proxmox host, run:
        chown -R $((100000 + $(id -u "$SERVICE_USER"))):$((100000 + $(id -g "$SERVICE_USER"))) <host folder>
    (assuming the default mapping), or reinstall with --uid/--gid matching the
    folder's owner minus 100000.
EOF
	else
		echo "    Run: chown -R $SERVICE_USER:$SERVICE_USER ${NOT_WRITABLE[*]}" >&2
	fi
	echo "    Or pick other folders when the dashboard's setup asks." >&2
fi
if [[ "$CONTAINER" == "lxc" ]]; then
	echo
	echo "${DIM}Tip: bind-mount music from the Proxmox host with"
	echo "  pct set <id> -mp0 /tank/music,mp=$MUSIC_DIR"
	echo "then point the Store and Inbox at folders inside $MUSIC_DIR.${RESET}"
fi
