"""Public common R7 foundation; exposure and skin ROI remain caller inputs.

These are the adopted R5 common clear-look settings carried into r7_final.
No scene exception, photo exposure, ROI, or source-location metadata is included.
WB W1 (+22 mired) belongs to the preceding profile-aware RAW decode.
"""
from __future__ import annotations

import r6_diagnose as r
import r7_look as k
import r7b_skin as sk
import r7c_punch as cp

# r7_final explicitly replaces the original common RGB tint with unity.
FOUNDATION = {"ev": .3, "contrast": 1.23, "rgb": [1., 1., 1.],
              "saturation": 1.22, "skin_saturation": 1.02,
              "yellow_rgb": [1., .995, 1.2]}


def stages(linear, skin_roi, external_base_ev):
    """Pure boundary B, without decode/WB, gamut mapping, or Stage Clear strength."""
    profile = k.profile_layer(linear, k.PROFILE_LEVELS["P2"])
    tone = k.local_tone(profile, **{key: k.IMMERSION_LEVELS["I1"][key]
                                  for key in ("highlights", "shadows")})
    foundation = r.foundation(tone, skin_roi, external_base_ev + sk.BRIGHTNESS_LEVELS["E2"], FOUNDATION)
    vibrance = k.vibrance(foundation, k.IMMERSION_LEVELS["I1"]["vibrance"])
    crisp = cp.crisp(vibrance, cp.CRISP_LEVELS["K1"])
    return profile, tone, foundation, vibrance, crisp, sk.skin_layer(crisp, sk.SKIN_LEVELS["S2"])


def render(linear, skin_roi, external_base_ev):
    return stages(linear, skin_roi, external_base_ev)[-1]
