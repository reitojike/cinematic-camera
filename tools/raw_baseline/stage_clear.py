"""Stage Clear v1: the look decided in rounds 7-10, as one function with a strength.

Input is the round-7 base (`r7_final.render`: decode -> profile P2 -> immersion I1 -> skin S2 ->
brightness E2 -> crispness K1 -> WB W1, all at a fixed exposure) and a face mask. Steps:

1. Glow: light-source bloom + halation (B1) and light diffusion (D1). Amounts scale with strength.
2. Iconic layer: black mist and Orton (light-adding, also reduced on bright photos by the
   near-white rule), vignette, vibrance. All scale with strength.
3. Skin chroma cap inside the face mask (round 10h "中"). Always on.
4. Face guard: if the brightest tenth of the face is above display 0.90, the glowed image is
   darkened (at most 0.7 EV) and step 2-3 are redone. Always on.
5. Highlight shoulder from display 0.80. Always on.

Strength 1 is the look the user approved in round 10j; 0 keeps only the round-7 colour base and
the protections (3-5). The face mask comes from a detector outside this module (YuNet on PC,
ML Kit in the app), so this module needs no OpenCV and is covered by CI tests.
"""
from __future__ import annotations

import numpy as np

import r6_diagnose as r
import r7_look as k
import r7b_skin as sk
import r10_iconic as ic
import r10d_character as cd
import r10e_portrait as pe
import r10f_combo as cb
import r10h_refine as h
import r10i_highlights as hi

VERSION = "stage-clear-v1"
GLOW = cd.STANDARD  # bloom/halation B1 + diffusion D1 (round 10c/10d)
VIGNETTE = .35
VIBRANCE = cb.VIBRANCE  # .35
STRENGTHS = (0., .5, 1.)  # comparison points; the default is decided by the user


def scaled_glow(strength: float) -> dict:
    return dict(GLOW, bloom=GLOW["bloom"] * strength, halation=GLOW["halation"] * strength,
                diffusion=GLOW["diffusion"] * strength)


def skin_weight(base: np.ndarray) -> np.ndarray:
    """Face-like skin weight used to protect skin from the light-adding steps."""
    return np.clip(ic.blur(sk.skin_weight(r.oklab(base)), .01), 0, 1)


def iconic_layer(glowed: np.ndarray, skin: np.ndarray, faces: np.ndarray, strength: float) -> np.ndarray:
    """Black mist -> Orton -> vignette -> vibrance (scaled), then the skin cap inside faces (always)."""
    auto = h.auto_strength(glowed) * strength
    out = glowed + auto * (pe.black_mist(glowed, skin) - glowed)
    out = out + auto * (h.orton(out, skin) - out)
    out = k.vibrance(pe.vignette(out, VIGNETTE * strength), VIBRANCE * strength)
    _, knee, slope, lift, _ = h.LEVELS[h.CHOSEN_LEVEL]
    return h.skin_chroma_cap(out, faces, knee, slope, lift, .24)


def render(base: np.ndarray, faces: np.ndarray, strength: float = 1.) -> tuple[np.ndarray, dict]:
    """Stage Clear at `strength` (0..1) from the round-7 base and a soft face mask (0..1)."""
    if not 0 <= strength <= 1:
        raise ValueError("strength must be within 0..1")
    skin = skin_weight(base)
    glowed = ic.glow(base, scaled_glow(strength)) if strength > 0 else base
    first = iconic_layer(glowed, skin, faces, strength)
    factor = hi.face_cut(first, faces)
    layered = iconic_layer(glowed * factor, skin, faces, strength) if factor < 1 else first
    info = {"version": VERSION, "strength": strength, "face_cut_ev": float(np.log2(factor)),
            "glow_strength": h.auto_strength(glowed * factor) * strength}
    return hi.shoulder(layered), info
