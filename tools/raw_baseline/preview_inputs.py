"""Synthetic-reference boundary for diagnostic preview inputs; no file/photo/decode I/O.

The caller supplies plain as-shot linear sRGB before W1, not an R7 base.
This only reuses public PC diagnostic metering and explicit foundation ellipses.
It neither runs a face detector nor supplies the unfinished face-priority AE.
"""
import numpy as np

import r6_diagnose as r
import r9_pixel as pixel


def prepare(linear, ellipses):
    h, w = linear.shape[:2]
    if max(w, h) > 1024 or not (3 * w // 4 > w // 4 and 3 * h // 4 > h // 4):
        raise ValueError("Invalid preview dimensions")
    if len(ellipses) > 6 or not np.isfinite(linear).all():
        raise ValueError("Invalid preview inputs")
    for cx, cy, rx, ry in ellipses:
        if not np.isfinite([cx, cy, rx, ry]).all() or not (0 <= cx <= 1 and 0 <= cy <= 1
                                                                       and 0 < rx <= 1 and 0 < ry <= 1):
            raise ValueError("Invalid normalized foundation ellipse")
    central = linear[h // 4:3 * h // 4, w // 4:3 * w // 4] @ r.LUMA
    p97 = float(np.percentile(central, 97))
    ev = pixel.auto_base_ev(linear)
    if not np.isfinite([p97, ev]).all():
        raise ValueError("Invalid diagnostic exposure")
    return ev, p97, central.size, r.skin_mask(w, h, ellipses)
