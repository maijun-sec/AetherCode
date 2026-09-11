"""Decoding layer for SSD300 (FR-5).

This module has two responsibilities, both expressed here so a reader can
trace every box that ends up in a :class:`Detection` back to either:

1. The canonical SSD box-decoding math (Liu et al., 2016, Eq. 2) — exposed
   as :func:`decode_boxes_demo`. This is **not** called by the production
   pipeline; it lives here as a well-commented reference implementation so
   a reader can understand the per-scale prior → xyxy transform without
   having to read the SSD paper. The actual decoder used in production
   is torchvision's internal ``SSD.forward``, which we wrap with
   :func:`to_detections`.

2. A thin adapter :func:`to_detections` that takes torchvision's
   ``list[{boxes, labels, scores}]`` eval-mode output, scales boxes
   from input-image (300×300) coordinates to **original-image**
   coordinates, applies a score threshold, and packages the result
   into a flat :class:`Detection` list sorted by descending score.

Why boxes live in **original-image** coordinates
------------------------------------------------
Design §5.3 / §5.5 leaves this as an open design decision; we
commit to original-image coordinates here so downstream
``nms.per_class_nms`` and ``draw.annotate_image`` operate in the
natural coordinate system of the actual image a human looks at.
This means ``to_detections`` needs the original ``(width, height)``
as a parameter — we trade a tiny caller-side hassle for a much
cleaner downstream API.

Coordinate-system notes for ``Detection.box``
----------------------------------------------
* Coordinates are in **pixels** (not normalized).
* ``x1 ≤ x2`` and ``y1 ≤ y2`` always (we filter degenerate boxes
  out of ``to_detections``).
* ``(x1, y1)`` is the top-left corner; ``(x2, y2)`` is the bottom-right.
* Out-of-image boxes are NOT clipped here; the caller is responsible
  for clipping at draw time (PIL clips automatically when drawing
  past image edges).
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import torch

__all__ = ["Detection", "decode_boxes_demo", "to_detections"]


# ----------------------------------------------------------------------------
# Canonical SSD variance priors
# ----------------------------------------------------------------------------
# From Liu et al., "SSD: Single Shot MultiBox Detector" (2016), Eq. 2:
#
#     x_center = cx + CENTER_VARIANCE * dx * w
#     y_center = cy + CENTER_VARIANCE * dy * h
#     w        = w * exp(SIZE_VARIANCE * dw)
#     h        = h * exp(SIZE_VARIANCE * dh)
#
# These are NOT learned — they are hyper-parameters baked into the SSD
# family of detectors. We expose them as module-level constants so the
# educational decoder and any future unit tests can refer to the same
# numbers used by the original paper.
_CENTER_VARIANCE: float = 0.1
_SIZE_VARIANCE: float = 0.2

# Input size SSD300 was trained at. torchvision's SSD300 is locked to
# 300×300; if torchvision ever ships a different size, change this.
_SSD_INPUT_SIZE: int = 300


# ----------------------------------------------------------------------------
# Detection dataclass — the public output type of the decoding layer
# ----------------------------------------------------------------------------

@dataclass(frozen=True)
class Detection:
    """A single detection in **original-image** pixel coordinates.

    Attributes:
        box: ``(x1, y1, x2, y2)`` in pixels. ``x1 ≤ x2`` and ``y1 ≤ y2``
            always hold. Values can lie slightly outside the image
            bounds; :func:`ssd_demo.draw.annotate_image` clips at
            draw time.
        score: model confidence in ``[0, 1]``. Higher = more confident.
        class_id: integer COCO category id (``1..91``). Background
            (``0``) is filtered out by :func:`to_detections`.
    """
    box: tuple[float, float, float, float]
    score: float
    class_id: int


# ----------------------------------------------------------------------------
# decode_boxes_demo — educational reference implementation of SSD Eq. 2
# ----------------------------------------------------------------------------

def decode_boxes_demo(
    cx: torch.Tensor,
    cy: torch.Tensor,
    w: torch.Tensor,
    h: torch.Tensor,
    offsets: torch.Tensor,
    clip_bounds: tuple[float, float] | None = None,
) -> torch.Tensor:
    """Reference implementation of SSD's per-box decode formula.

    This function is **not** used by the production pipeline; it is here
    so a reader who wants to understand "given a prior box (cx, cy, w, h)
    and a (dx, dy, dw, dh) network offset, how do we get back to xyxy
    image coordinates?" has a 25-line answer in front of them.

    Args:
        cx: ``(N,)`` prior center-x coordinates, in pixels (input-image
            scale, typically ``[0, 300]``).
        cy: ``(N,)`` prior center-y coordinates.
        w: ``(N,)`` prior widths, in pixels.
        h: ``(N,)`` prior heights, in pixels.
        offsets: ``(N, 4)`` raw network outputs per box, in the order
            ``(dx, dy, dw, dh)``. These are deltas, not absolute coords.
        clip_bounds: optional ``(min, max)`` tuple. If given, each
            coordinate in the output is clamped to ``[min, max]``.
            Useful for keeping decoded boxes inside an image. The SSD
            paper clamps to ``[0, 1]`` in normalized coordinates; pass
            ``clip_bounds=(0.0, 1.0)`` to replicate that.

    Returns:
        ``(N, 4)`` float tensor of xyxy boxes, in the same coordinate
        system as the inputs.

    Raises:
        ValueError: if ``offsets`` does not have a trailing dimension
            of size 4, or if ``cx``/``cy``/``w``/``h`` are not 1-D.

    Example:
        Zero offsets → boxes are exactly the priors::

            priors = torch.tensor([150., 150., 20., 10.])  # cx, cy, w, h
            offsets = torch.zeros(1, 4)
            xyxy = decode_boxes_demo(*priors, offsets)  # → tensor([[140., 145., 160., 155.]])
    """
    # Shape validation. Done up-front for clearer error messages.
    if offsets.ndim != 2 or offsets.shape[-1] != 4:
        raise ValueError(
            f"offsets must have shape (N, 4); got {tuple(offsets.shape)}"
        )
    for name, t in (("cx", cx), ("cy", cy), ("w", w), ("h", h)):
        if t.ndim != 1:
            raise ValueError(f"{name} must be 1-D; got shape {tuple(t.shape)}")
    n = offsets.shape[0]
    for name, t in (("cx", cx), ("cy", cy), ("w", w), ("h", h)):
        if t.shape[0] != n:
            raise ValueError(
                f"{name} length {t.shape[0]} != offsets length {n}"
            )

    # Slice the offsets. ``[..., 0]`` returns a 1-D tensor of length N.
    dx = offsets[:, 0]
    dy = offsets[:, 1]
    dw = offsets[:, 2]
    dh = offsets[:, 3]

    # Canonical SSD decode (Eq. 2 in the paper).
    decoded_cx = cx + _CENTER_VARIANCE * dx * w
    decoded_cy = cy + _CENTER_VARIANCE * dy * h
    decoded_w = w * torch.exp(_SIZE_VARIANCE * dw)
    decoded_h = h * torch.exp(_SIZE_VARIANCE * dh)

    # xyxy corners from center + size.
    x1 = decoded_cx - decoded_w / 2.0
    y1 = decoded_cy - decoded_h / 2.0
    x2 = decoded_cx + decoded_w / 2.0
    y2 = decoded_cy + decoded_h / 2.0

    boxes = torch.stack([x1, y1, x2, y2], dim=-1)

    if clip_bounds is not None:
        lo, hi = clip_bounds
        boxes = boxes.clamp(min=lo, max=hi)

    return boxes


# ----------------------------------------------------------------------------
# to_detections — torchvision-output adapter
# ----------------------------------------------------------------------------

def to_detections(
    model_output: list[dict[str, torch.Tensor]],
    orig_size: tuple[int, int],
    score_threshold: float = 0.0,
) -> list[Detection]:
    """Convert a batched torchvision SSD300 eval-mode output into a
    flat list of :class:`Detection` objects in **original-image**
    pixel coordinates.

    The conversion does four things:
      1. Scales ``boxes`` from input-image coords (300×300) to
         original-image coords using ``orig_size``.
      2. Filters out ``class_id == 0`` (the SSD "background" class)
         defensively — torchvision's postprocess already drops it,
         but the guard is cheap and protects against future API drift.
      3. Drops detections with ``score < score_threshold``.
      4. Sorts the result by score descending, so callers can
         slice ``detections[:top_k]`` without re-sorting.

    Args:
        model_output: a list (one entry per batch element) of dicts
            with keys:
              * ``"boxes"``:  ``(N_i, 4)`` float tensor, xyxy in
                input-image scale (300×300).
              * ``"labels"``: ``(N_i,)`` long tensor of int class IDs.
              * ``"scores"``: ``(N_i,)`` float tensor of confidences
                in ``[0, 1]``.

            This is exactly the shape returned by
            ``torchvision.models.detection.ssd300_vgg16(...)`` when
            called in ``eval()`` mode.

        orig_size: ``(width, height)`` of the **original** image
            that was fed into preprocessing, in pixels. Boxes are
            scaled by ``(orig_w / 300, orig_h / 300)`` per-axis.
            Non-uniform scaling is supported (which is what our
            preprocessing actually does — it stretches, not
            letterboxes).

        score_threshold: drop detections with score below this.
            ``0.0`` keeps everything (useful for tests that want
            raw output); the CLI default is ``0.5`` per spec FR-6.

    Returns:
        A flat ``list[Detection]`` covering all batch entries,
        sorted by score descending. Empty if ``model_output`` is
        empty or all detections are below threshold.

    Notes:
        Per the design contract, ``Detection.box`` is in original-image
        coordinates. Callers should not multiply by the scale factor
        again.
    """
    if not model_output:
        return []

    orig_w, orig_h = orig_size
    scale_x = orig_w / _SSD_INPUT_SIZE
    scale_y = orig_h / _SSD_INPUT_SIZE

    detections: list[Detection] = []
    for per_image in model_output:
        boxes = per_image["boxes"]    # (N, 4) xyxy in 300×300
        labels = per_image["labels"]  # (N,)
        scores = per_image["scores"]  # (N,)

        if boxes.numel() == 0:
            continue

        # Scale (300,300) → original (orig_w, orig_h). Apply per-axis
        # because preprocessing stretches rather than letterboxes.
        scaled = boxes.clone()
        scaled[:, 0].mul_(scale_x)   # x1
        scaled[:, 1].mul_(scale_y)   # y1
        scaled[:, 2].mul_(scale_x)   # x2
        scaled[:, 3].mul_(scale_y)   # y2

        # Pull to CPU / Python once, then iterate. Avoids per-element
        # .item() calls on tensors.
        boxes_list = scaled.tolist()
        labels_list = labels.tolist()
        scores_list = scores.tolist()

        for box, class_id, score in zip(boxes_list, labels_list, scores_list):
            class_id = int(class_id)
            if class_id == 0:
                # Defensive: torchvision already drops background, but
                # if a future API change passes it through, we silently
                # skip rather than returning a junk detection.
                continue
            score_f = float(score)
            if score_f < score_threshold:
                continue
            x1, y1, x2, y2 = (float(c) for c in box)
            # Filter degenerate boxes (zero-area or inverted corners).
            # torchvision's NMS still tolerates these, but they're noise.
            if x2 <= x1 or y2 <= y1:
                continue
            detections.append(Detection(
                box=(x1, y1, x2, y2),
                score=score_f,
                class_id=class_id,
            ))

    # Sort by score descending. ``reverse=True`` + key=lambda is the
    # canonical Python idiom; stable sort preserves original order
    # within equal-score groups.
    detections.sort(key=lambda d: d.score, reverse=True)
    return detections
