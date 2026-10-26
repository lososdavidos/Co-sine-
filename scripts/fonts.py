#!/usr/bin/env python3
"""Builds Phase's fonts from the variable sources in third_party/fonts.

Sine gets fixed instances, one file per role, so Android draws them the same
on every version: Instrument Sans at three widths and Martian Mono at the
width Phase sets data in. Cosine's dashboard gets both families whole, as
variable WOFF2, served from the binary. Both keep only the characters
Latin-script music needs; anything else falls back to the system's font.

    pip install fonttools brotli
    python3 scripts/fonts.py

The output is committed, so this only runs again when the sources or the
roles below change.
"""

import os
import shutil

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "third_party", "fonts")
ANDROID = os.path.join(ROOT, "sine", "app", "src", "main", "res", "font")
ANDROID_LICENSES = os.path.join(ROOT, "sine", "app", "src", "main", "assets", "licenses")
WEB = os.path.join(ROOT, "cosine", "internal", "dashboard", "static", "fonts")

SANS = "InstrumentSans-Variable.ttf"
MONO = "MartianMono-Variable.ttf"

# Basic Latin, Latin-1, Latin Extended-A and B, general punctuation, the
# euro, arrows and the minus sign: what names, titles and numbers use.
UNICODES = (
    list(range(0x0020, 0x007F))
    + list(range(0x00A0, 0x0250))
    + list(range(0x2010, 0x2040))
    + [0x20AC, 0x2122, 0x2190, 0x2191, 0x2192, 0x2193, 0x2212]
)

# Sine's roles (see Phase, Foundations → Type).
ANDROID_FONTS = {
    "phase_display.ttf": (SANS, {"wdth": 75, "wght": 700}),
    "phase_title.ttf": (SANS, {"wdth": 85, "wght": 600}),
    "phase_sans_regular.ttf": (SANS, {"wdth": 100, "wght": 400}),
    "phase_sans_medium.ttf": (SANS, {"wdth": 100, "wght": 500}),
    "phase_sans_semibold.ttf": (SANS, {"wdth": 100, "wght": 600}),
    "phase_mono_regular.ttf": (MONO, {"wdth": 87.5, "wght": 400}),
    "phase_mono_medium.ttf": (MONO, {"wdth": 87.5, "wght": 500}),
    "phase_mono_semibold.ttf": (MONO, {"wdth": 87.5, "wght": 600}),
}

WEB_FONTS = {
    "instrument-sans.woff2": SANS,
    "martian-mono.woff2": MONO,
}


def subsetted(font, flavor=None):
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.flavor = flavor
    s = subset.Subsetter(options)
    s.populate(unicodes=UNICODES)
    s.subset(font)
    return font


def main():
    os.makedirs(ANDROID, exist_ok=True)
    os.makedirs(ANDROID_LICENSES, exist_ok=True)
    os.makedirs(WEB, exist_ok=True)

    for name, (source, axes) in ANDROID_FONTS.items():
        font = instancer.instantiateVariableFont(TTFont(os.path.join(SRC, source)), axes)
        subsetted(font).save(os.path.join(ANDROID, name))
        print(name, os.path.getsize(os.path.join(ANDROID, name)))

    for name, source in WEB_FONTS.items():
        font = subsetted(TTFont(os.path.join(SRC, source)), flavor="woff2")
        font.flavor = "woff2"
        font.save(os.path.join(WEB, name))
        print(name, os.path.getsize(os.path.join(WEB, name)))

    # The OFL travels with every copy of the fonts.
    for licence in ("InstrumentSans-OFL.txt", "MartianMono-OFL.txt"):
        for dest in (ANDROID_LICENSES, WEB):
            shutil.copy(os.path.join(SRC, licence), os.path.join(dest, licence))


if __name__ == "__main__":
    main()
