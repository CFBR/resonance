#!/usr/bin/env python3
"""Pre-seed a Resonance offline library from a computer over USB.

Enumerates songs from a Subsonic-compatible server (Navidrome), downloads each
file over the LAN, and lays it out under the exact path the Android app expects
on the storage folder you picked, plus a manifest the app's "Import" turns into
ready offline records. The first big fill then takes minutes of USB instead of
days of Wi-Fi.

The path and key rules mirror the app's DownloadFile.kt byte for byte:
  key  = sha256(f"{server}/rest/download\\0{user}\\0{song_id}")
  path = Resonance/<artist>/<album>/<disc-track - title [key[:12]>.<ext>

Usage:
  resonance_preseed.py --server URL --user NAME --password PW [--album-id ID]
      --out DIR [--push-serial SERIAL --push-root /storage/XXXX/Music]

Credentials are taken from arguments only. With --push-serial, files and the
manifest are pushed with adb to the storage root given by --push-root.
"""

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request

SUFFIX_MAP = {
    "audio/mpeg": "mp3", "audio/flac": "flac", "audio/x-flac": "flac",
    "audio/mp4": "m4a", "audio/x-m4a": "m4a", "audio/ogg": "ogg",
    "application/ogg": "ogg", "audio/opus": "opus", "audio/wav": "wav",
    "audio/x-wav": "wav", "audio/aac": "aac",
}
SUFFIX_RE = re.compile(r"^[a-z0-9]{1,10}$")
MANIFEST_NAME = "resonance-manifest.jsonl"
PAGE_SIZE = 200
SONG_PAGE = 500


def auth_params(args):
    salt = hashlib.sha256(os.urandom(16)).hexdigest()
    token = hashlib.md5((args.password + salt).encode()).hexdigest()
    return {"u": args.user, "t": token, "s": salt, "v": "1.16.1",
            "c": "ResonancePreseed", "f": "json"}


def call(server, params, endpoint, timeout=120):
    qs = urllib.parse.urlencode(params)
    with urllib.request.urlopen(f"{server}/rest/{endpoint}?{qs}", timeout=timeout) as r:
        body = json.load(r)["subsonic-response"]
    if body.get("status") != "ok":
        raise RuntimeError(f"{endpoint}: server error {body.get('error')}")
    return body


def record_key(server, user, song_id):
    return hashlib.sha256(f"{server}/rest/download\x00{user}\x00{song_id}".encode()).hexdigest()


TRIM_EDGE = "".join(chr(c) for c in range(0x21))


def kotlin_trim(text):
    """Kotlin's trim() removes chars up to U+0020 only, unlike str.strip()."""
    return text.strip(TRIM_EDGE)


def safe_component(value, fallback):
    cleaned = "".join("_" if (ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F or c in '/\\:*?"<>|') else c
                      for c in (value or ""))
    cleaned = kotlin_trim(cleaned).strip(".").strip(TRIM_EDGE)
    out, size = [], 0
    for ch in cleaned:
        size += len(ch.encode("utf-8"))
        if size > 160:
            break
        out.append(ch)
    result = kotlin_trim("".join(out)).rstrip(".")
    return result or fallback


def song_path(song, key):
    artist = safe_component(song.get("artist"), "Unknown artist")
    album = safe_component(song.get("album"), "Unknown album")
    title = safe_component(song.get("title"), "Untitled")
    # Mirrors listOfNotNull(disc, track).joinToString("-"): a disc alone
    # still produces a numbering, unlike the common two-part assumption.
    parts = []
    if song.get("discNumber") and song["discNumber"] > 0:
        parts.append(str(song["discNumber"]))
    if song.get("track") and song["track"] > 0:
        parts.append(f"{song['track']:02d}")
    numbering = f"{'-'.join(parts)} - " if parts else ""
    suffix = (song.get("suffix") or "").lower()
    if not SUFFIX_RE.match(suffix):
        content = (song.get("contentType") or "").lower().split(";")[0]
        suffix = SUFFIX_MAP.get(content, "")
    extension = f".{suffix}" if suffix else ""
    return f"Resonance/{artist}/{album}/{numbering}{title} [{key[:12]}]{extension}"


def manifest_song(song):
    """Only the fields the app's SongItem carries, nulls omitted like Gson."""
    fields = ["id", "title", "album", "albumId", "artist", "artistId", "track",
              "year", "genre", "coverArt", "size", "contentType", "suffix",
              "duration", "bitRate", "path", "discNumber", "starred"]
    return {f: song[f] for f in fields if song.get(f) is not None}


def list_albums(server, params, offset):
    body = call(server, {**params, "type": "alphabeticalByName",
                         "size": PAGE_SIZE, "offset": offset}, "getAlbumList2")
    return body.get("albumList2", {}).get("album", [])


def album_songs(server, params, album_id):
    body = call(server, {**params, "id": album_id}, "getAlbum")
    return body.get("album", {}).get("song", [])


def all_songs(server, params):
    """Every song the server knows, in one paged sweep.

    An empty search matches everything, so the whole library costs a handful
    of requests instead of one per album.
    """
    songs, offset = [], 0
    while True:
        body = call(server, {**params, "query": "", "songCount": SONG_PAGE,
                             "songOffset": offset, "albumCount": 0,
                             "artistCount": 0}, "search3")
        page = body.get("searchResult3", {}).get("song", [])
        songs += page
        if len(page) < SONG_PAGE:
            return songs
        offset += SONG_PAGE


def device_files(serial, root):
    """Files already under the push root, as manifest-style relative paths.

    One adb call lists the whole tree, so resuming costs seconds instead of
    one round trip per song.
    """
    listing = subprocess.run(["adb", "-s", serial, "shell",
                              f"find {shlex.quote(root)} -type f 2>/dev/null"],
                             capture_output=True, text=True, check=False)
    paths = set()
    prefix = root.rstrip("/") + "/"
    for line in listing.stdout.splitlines():
        line = line.strip()
        if line.startswith(prefix):
            paths.add(line[len(prefix):])
    return paths


def download_song(server, params, song_id, destination, timeout=600):
    qs = urllib.parse.urlencode({**params, "id": song_id})
    with urllib.request.urlopen(f"{server}/rest/download?{qs}", timeout=timeout) as r:
        content_type = (r.headers.get("Content-Type") or "").split(";")[0].strip()
        if content_type.startswith("text/"):
            raise RuntimeError(f"download of {song_id} returned {content_type}, not audio")
        with open(destination, "wb") as f:
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--album-id", help="limit to one album (testing)")
    parser.add_argument("--all", action="store_true", help="walk the entire library")
    parser.add_argument("--out", required=True, help="local directory for files and the manifest")
    parser.add_argument("--push-serial", help="adb device serial to push to")
    parser.add_argument("--push-root", help="the storage root on the device, e.g. /storage/XXXX/Music")
    parser.add_argument("--stream", action="store_true",
                        help="push each album and delete it locally instead of staging everything, "
                             "so disk use stays at one album no matter the library size")
    parser.add_argument("--resume", action="store_true",
                        help="skip songs whose file already sits on the device, so an "
                             "interrupted run continues instead of downloading everything again")
    parser.add_argument("--manifest-only", action="store_true",
                        help="rebuild the manifest from the files already on the device and "
                             "stop; nothing is downloaded or transferred but the manifest")
    args = parser.parse_args()
    if not (args.album_id or args.all or args.manifest_only):
        parser.error("pass --album-id, --all or --manifest-only")

    # A killed run must not swallow its log, so do not sit on buffered output.
    sys.stdout.reconfigure(line_buffering=True)

    server = args.server.rstrip("/")
    params = auth_params(args)
    os.makedirs(args.out, exist_ok=True)
    done = 0

    if args.album_id:
        albums = [{"id": args.album_id}]
    elif args.manifest_only:
        albums = []
    else:
        albums, offset = [], 0
        while True:
            page = list_albums(server, params, offset)
            albums += page
            if len(page) < PAGE_SIZE:
                break
            offset += PAGE_SIZE

    root = (args.push_root or "/storage/emulated/0/Music") if args.push_serial else None

    manifest = os.path.join(args.out, MANIFEST_NAME)
    manifest_lines = {}
    if args.resume and os.path.exists(manifest):
        # Continue the list where the last run stopped; the final rewrite
        # drops any duplicate left by an interrupted append.
        with open(manifest, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    manifest_lines[json.loads(line)["path"]] = line
                except (ValueError, KeyError, TypeError):
                    # A run killed mid-write can leave a torn final line; the
                    # song gets noted again when the walk reaches it.
                    continue
        print(f"resuming with {len(manifest_lines)} manifest entries", flush=True)

    def note(path, song):
        line = json.dumps({"path": path, "song": manifest_song(song)},
                          ensure_ascii=False, separators=(",", ":"))
        if manifest_lines.get(path) == line:
            return
        manifest_lines[path] = line
        with open(manifest, "a", encoding="utf-8") as f:
            f.write(line + "\n")

    existing = set()
    if args.resume and args.push_serial:
        existing = device_files(args.push_serial, root)
        print(f"{len(existing)} files already on the device", flush=True)

    if args.manifest_only:
        if not (args.push_serial and root):
            parser.error("--manifest-only needs --push-serial and --push-root")
        existing = device_files(args.push_serial, root)
        songs = all_songs(server, params)
        print(f"{len(existing)} files on the device, {len(songs)} songs on the server", flush=True)
        for song in songs:
            key = record_key(server, args.user, song["id"])
            path = song_path(song, key)
            if path in existing:
                note(path, song)
        with open(manifest, "w", encoding="utf-8") as f:
            f.write("\n".join(manifest_lines.values()) + ("\n" if manifest_lines else ""))
        subprocess.run(["adb", "-s", args.push_serial, "push", manifest, f"{root}/"], check=True)
        print(f"done: {len(manifest_lines)} entries in the manifest", flush=True)
        return 0

    def push_directory(local_dir, remote_parent):
        # adb push creates the leaf but not always the chain, so make the
        # parent explicitly. The path runs through the device shell, so quote
        # it: artist and album names contain spaces.
        subprocess.run(["adb", "-s", args.push_serial, "shell",
                        f"mkdir -p {shlex.quote(remote_parent)}"], check=True)
        subprocess.run(["adb", "-s", args.push_serial, "push", local_dir, remote_parent + "/"],
                       check=True)

    for album in albums:
        staged = []
        for song in album_songs(server, params, album["id"]):
            key = record_key(server, args.user, song["id"])
            path = song_path(song, key)
            note(path, song)
            done += 1
            if path in existing:
                print(f"[{done}] {path} (on device)")
                continue
            destination = os.path.join(args.out, *path.split("/"))
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            if not os.path.exists(destination):
                download_song(server, params, song["id"], destination)
            staged.append(os.path.dirname(destination))
            print(f"[{done}] {path}")

        if args.stream and args.push_serial and staged:
            # Songs in one album can name different albums, so delete every
            # directory this album touched; keeping only the last one leaked
            # the rest.
            for local_dir in dict.fromkeys(staged):
                relative = os.path.relpath(local_dir, args.out)
                artist_parent = "/".join(relative.split("/")[:-1])
                push_directory(local_dir, f"{root}/{artist_parent}")
            for local_dir in dict.fromkeys(staged):
                shutil.rmtree(local_dir, ignore_errors=True)

    with open(manifest, "w", encoding="utf-8") as f:
        f.write("\n".join(manifest_lines.values()) + ("\n" if manifest_lines else ""))

    if args.push_serial and not args.stream:
        print(f"pushing {done} files and the manifest to {root}")
        push_directory(os.path.join(args.out, "Resonance"), root)
    if args.push_serial:
        subprocess.run(["adb", "-s", args.push_serial, "push", manifest, f"{root}/"], check=True)

    print(f"done: {done} songs, manifest at {manifest}")


if __name__ == "__main__":
    sys.exit(main())
