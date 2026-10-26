"""Generate a small tagged test library (mp3, m4a, flac, ogg, opus) for Track Record.

Usage: python make_test_library.py <ffmpeg.exe> <out_dir>
Prints the expected (title, original year, tag year, effective year) per file.
"""
import base64
import os
import subprocess
import sys

from mutagen.flac import FLAC, Picture
from mutagen.id3 import APIC, ID3, TALB, TDOR, TDRC, TIT2, TORY, TPE1, TSRC, TXXX, TYER, UFID
from mutagen.mp4 import MP4, MP4FreeForm
from mutagen.oggopus import OggOpus
from mutagen.oggvorbis import OggVorbis

FFMPEG, OUT = sys.argv[1], sys.argv[2]
MBID = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
# 1x1 PNG
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

codec = {"mp3": ["-c:a", "libmp3lame", "-b:a", "96k"], "m4a": ["-c:a", "aac", "-b:a", "96k"],
         "flac": ["-c:a", "flac"], "ogg": ["-c:a", "libvorbis"], "opus": ["-c:a", "libopus", "-b:a", "64k"]}


def tone(path, freq, seconds=12):
    ext = path.rsplit(".", 1)[1]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"sine=frequency={freq}:duration={seconds}",
                    "-ac", "2", "-map_metadata", "-1", *codec[ext], path], check=True)


def mp3(name, freq, v23=False, **t):
    path = os.path.join(OUT, name)
    tone(path, freq)
    tags = ID3()
    tags.add(TIT2(encoding=3, text=t["title"]))
    tags.add(TPE1(encoding=3, text=t["artist"]))
    if "album" in t: tags.add(TALB(encoding=3, text=t["album"]))
    if v23:
        if "orig" in t: tags.add(TORY(encoding=3, text=t["orig"]))
        if "date" in t: tags.add(TYER(encoding=3, text=t["date"]))
    else:
        if "orig" in t: tags.add(TDOR(encoding=3, text=t["orig"]))
        if "date" in t: tags.add(TDRC(encoding=3, text=t["date"]))
    if "txxx_origyear" in t: tags.add(TXXX(encoding=3, desc="originalyear", text=t["txxx_origyear"]))
    if t.get("mbid"): tags.add(UFID(owner="http://musicbrainz.org", data=MBID.encode()))
    if t.get("isrc"): tags.add(TSRC(encoding=3, text=t["isrc"]))
    if t.get("cover"): tags.add(APIC(encoding=3, mime="image/png", type=3, desc="Cover", data=PNG))
    tags.save(path, v2_version=3 if v23 else 4)


def m4a(name, freq, **t):
    path = os.path.join(OUT, name)
    tone(path, freq)
    f = MP4(path)
    f["\xa9nam"] = t["title"]
    f["\xa9ART"] = t["artist"]
    if "date" in t: f["\xa9day"] = t["date"]
    if "orig" in t: f["----:com.apple.iTunes:originaldate"] = MP4FreeForm(t["orig"].encode())
    if t.get("mbid"): f["----:com.apple.iTunes:MusicBrainz Track Id"] = MP4FreeForm(MBID.encode())
    if t.get("isrc"): f["----:com.apple.iTunes:ISRC"] = MP4FreeForm(t["isrc"].encode())
    f.save()


def vorbis_like(cls, name, freq, **t):
    path = os.path.join(OUT, name)
    tone(path, freq)
    f = cls(path)
    f["TITLE"] = t["title"]
    f["ARTIST"] = t["artist"]
    if "date" in t: f["DATE"] = t["date"]
    if "orig" in t: f["ORIGINALDATE"] = t["orig"]
    if t.get("mbid"): f["MUSICBRAINZ_TRACKID"] = MBID
    if t.get("isrc"): f["ISRC"] = t["isrc"]
    if t.get("cover") and cls is FLAC:
        p = Picture(); p.type = 3; p.mime = "image/png"; p.data = PNG; p.width = p.height = 1; p.depth = 24
        f.add_picture(p)
    f.save()


mp3("01 Queen - Bohemian Rhapsody.mp3", 330, title="Bohemian Rhapsody (Remastered 2011)", artist="Queen",
    album="A Night at the Opera", orig="1975-10-31", date="2011-01-01", mbid=True, isrc="GBUM71029604", cover=True)
mp3("02 David Bowie - Space Oddity.mp3", 350, v23=True, title="Space Oddity", artist="David Bowie", orig="1969", date="2009")
mp3("03 Unknown - No Year.mp3", 370, title="No Year Song", artist="Nobody")
mp3("04 Nena - 99 Luftballons.mp3", 390, title="99 Luftballons", artist="Nena", date="1983")
mp3("05 Bad Year.mp3", 410, title="Bogus Year", artist="Tester", date="0000")
mp3("06 TXXX Original.mp3", 430, title="Heroes", artist="David Bowie", date="2017", txxx_origyear="1977")
m4a("07 John Lennon - Imagine.m4a", 450, title="Imagine", artist="John Lennon", date="2015-06-01", orig="1971-10-11", mbid=True, isrc="GBAYE0601477")
m4a("08 No Year.m4a", 470, title="M4A Without Year", artist="Nobody")
vorbis_like(FLAC, "09 Toto - Africa.flac", 490, title="Africa", artist="Toto", date="1999", orig="1982", mbid=True, isrc="USSM18200521", cover=True)
vorbis_like(OggVorbis, "10 ABBA - Dancing Queen.ogg", 510, title="Dancing Queen", artist="ABBA", date="1976")
vorbis_like(OggOpus, "11 Beatles - Yesterday.opus", 530, title="Yesterday", artist="The Beatles", date="2001", orig="1965")
mp3("Sub/12 Nirvana - Smells Like Teen Spirit.mp3", 550, title="Smells Like Teen Spirit", artist="Nirvana", date="1991-09-10")
print("generated", sum(len(f) for _, _, f in os.walk(OUT)), "files in", OUT)
