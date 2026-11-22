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
    args = parser.parse_args()
    if not (args.album_id or args.all):
        parser.error("pass --album-id or --all")

    server = args.server.rstrip("/")
    params = auth_params(args)
    os.makedirs(args.out, exist_ok=True)
    manifest_lines = []
    done = 0

    if args.album_id:
        albums = [{"id": args.album_id}]
    else:
        albums, offset = [], 0
        while True:
            page = list_albums(server, params, offset)
            albums += page
            if len(page) < PAGE_SIZE:
                break
            offset += PAGE_SIZE

    for album in albums:
        for song in album_songs(server, params, album["id"]):
            key = record_key(server, args.user, song["id"])
            path = song_path(song, key)
            destination = os.path.join(args.out, *path.split("/"))
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            if not os.path.exists(destination):
                download_song(server, params, song["id"], destination)
            manifest_lines.append(json.dumps({"path": path, "song": manifest_song(song)},
                                              ensure_ascii=False, separators=(",", ":")))
            done += 1
            print(f"[{done}] {path}")

    manifest = os.path.join(args.out, MANIFEST_NAME)
    with open(manifest, "w", encoding="utf-8") as f:
        f.write("\n".join(manifest_lines) + ("\n" if manifest_lines else ""))

    if args.push_serial:
        import subprocess
        root = args.push_root or "/storage/emulated/0/Music"
        print(f"pushing {done} files and the manifest to {root}")
        subprocess.run(["adb", "-s", args.push_serial, "push",
                        os.path.join(args.out, "Resonance"), f"{root}/"], check=True)
        subprocess.run(["adb", "-s", args.push_serial, "push", manifest, f"{root}/"], check=True)

    print(f"done: {done} songs, manifest at {manifest}")


if __name__ == "__main__":
    sys.exit(main())
