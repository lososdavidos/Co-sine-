# Installing Cosine on Proxmox

`install.sh` installs Cosine and everything it depends on inside a Debian or Ubuntu container or VM. It works in privileged and unprivileged LXC containers. Run it again to upgrade.

| Installed | Why | Kept current by |
|---|---|---|
| Cosine (`/opt/cosine/bin/cosine`) | The server | Rerunning the installer |
| yt-dlp (official standalone build) | Fetching from links | A daily `yt-dlp-update.timer` |
| Deno | The JavaScript runtime yt-dlp needs for YouTube | Rerunning the installer |
| ffmpeg | `ffprobe`, for track durations | apt |
| sqlite3 | The nightly database backup | apt |
| Chromaprint (`--with-fingerprinting`) | Audio fingerprinting, for later | apt |

Downloads are checked against their published SHA-256 checksums before anything is installed.

The metadata sources planned next (MusicBrainz, Discogs, Bandcamp) are web lookups built into Cosine, so they need nothing installed here. Anything that ever does will be added to the installer, and rerunning it picks it up.

## 1. Create the container

Debian 13 is the tested target. Debian 12 and Ubuntu 24.04 also work. 1 CPU core, 1 GB RAM and 8 GB of disk are plenty; your music lives on a mount point, not the root disk.

**Unprivileged (recommended)**, from the Proxmox host shell:

```sh
pveam update && pveam download local debian-13-standard_13.1-2_amd64.tar.zst   # check `pveam available` for the current name
pct create 120 local:vztmpl/debian-13-standard_13.1-2_amd64.tar.zst \
  --hostname cosine --unprivileged 1 --features nesting=1 \
  --cores 1 --memory 1024 --rootfs local-lvm:8 \
  --net0 name=eth0,bridge=vmbr0,ip=dhcp --onboot 1
```

**Privileged:** the same command with `--unprivileged 0`.

Keep **nesting** on. It lets systemd sandbox the Cosine service. Without it, the installer notices the service failing and starts it without the sandbox.

## 2. Give it your music (optional)

Bind-mount a host folder into the container, then choose folders inside it as the Store and Inbox during setup:

```sh
pct set 120 -mp0 /tank/music,mp=/srv/music
```

In an **unprivileged** container, UIDs are shifted: container UID N is host UID 100000+N by default. The installer prints the `cosine` user's UID. On the host, make the folder writable by the shifted ID, e.g. for UID 999:

```sh
chown -R 100999:100999 /tank/music
```

Alternatively, run the installer with `--uid`/`--gid` set to the folder's current owner minus 100000. A **privileged** container needs no shifting: `chown -R <uid>:<gid>` with the container's IDs directly.

The installer never changes ownership of a folder that already has files in it. It tells you if `cosine` can't write somewhere.

## 3. Install

```sh
pct start 120 && pct enter 120
apt-get update && apt-get install -y curl
bash -c "$(curl -fsSL https://github.com/lososdavidos/Co-sine-/releases/latest/download/install.sh)"
```

Then open `http://<container-ip>:4534/`. The first visit runs setup (Store, Inbox, admin account). Point Sine at the same address.

Options (`--help` for all):

| Flag | |
|---|---|
| `--version v0.3.0` | A specific release instead of the latest |
| `--binary ./cosine` | Install a binary you built, instead of downloading |
| `--listen :4534` | Listen address (first install only; later, edit `/etc/cosine/cosine.env`) |
| `--music-dir /srv/music` | Where the suggested Store and Inbox are created |
| `--uid N --gid N` | IDs for the `cosine` user, to match bind-mounted folders |
| `--with-fingerprinting` | Also install Chromaprint |
| `--no-deno`, `--no-backup`, `--no-start` | Skip those parts |
| `--uninstall [--purge]` | Remove Cosine; `--purge` also deletes its data. Music is never deleted |

## Afterwards

- **Settings:** `/etc/cosine/cosine.env`, then `systemctl restart cosine`.
- **Logs:** `journalctl -u cosine -f`.
- **Backups:** `/var/lib/cosine/backups/`, nightly, keeping the newest seven. They hold the database and `secret.key`, which together are everything that can't be re-fetched. Copy them off the container too, e.g. with Proxmox's own backup of the CT.
- **Upgrade:** run the install command again.

## Publishing a release

The installer downloads from GitHub releases. Pushing a version tag builds and publishes one (`.github/workflows/release.yml`):

```sh
git tag v0.2.0 && git push origin v0.2.0
```
