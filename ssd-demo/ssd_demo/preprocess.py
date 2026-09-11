"""Image preprocessing for SSD300 inference (FR-3).

Public surface
--------------
- :func:`load_image`  — read a file from disk into an RGB ``PIL.Image``.
- :func:`to_tensor`   — turn an RGB ``PIL.Image`` into a model-ready
  ``torch.Tensor`` of shape ``(1, 3, H, W)`` that SSD300 can consume.

Pipeline (also in design §7)
-----------------------------
1. Bilinear resize to ``input_size`` (default ``(300, 300)``). Aspect
   ratio is **not** preserved — we follow torchvision's default SSD300
   transform, which simply stretches. Adding letterboxing is the kind of
   detail that would push the demo over its 500-LOC budget; we deliberately
   skip it.
2. Convert to ``float32`` in ``[0, 1]`` by dividing by 255.
3. Permute HWC → CHW.
4. Normalize per channel with canonical ImageNet mean/std.
5. Add a leading batch dim → shape ``(1, 3, H, W)``.

Why module-level (non-lazy) torch + PIL imports
-----------------------------------------------
Unlike :mod:`ssd_demo.infer`, this module **is** the layer that produces
torch tensors — lazy-importing the very thing the function returns would
be theatre, not design. ``from ssd_demo.preprocess import to_tensor``
requires torch and PIL, which is consistent with the spec's runtime
dependency budget (NFR-3) and with the local environment (verified in
Phase 2: ``torch 2.12.0`` and ``Pillow 12.2.0`` available).

ImageNet constants
------------------
The constants below are the canonical ImageNet stats and are what
``torchvision.models.detection.SSD300_VGG16_Weights.DEFAULT.meta``
reports for ``"mean"`` and ``"std"``. We hardcode them rather than
importing from torchvision so this module stays usable without the
torchvision install (the same design choice we made for ``infer.py``).
"""
from __future__ import annotations

from pathlib import Path
from typing import Union

import torch
from PIL import Image

# Canonical ImageNet normalization stats. Matches what
# torchvision.models.detection.SSD300_VGG16_Weights.DEFAULT.meta reports.
_IMAGENET_MEAN: tuple[float, float, float] = (0.485, 0.456, 0.406)
_IMAGENET_STD: tuple[float, float, float] = (0.229, 0.224, 0.225)

# Spec FR-3: default input size is 300×300.
_DEFAULT_INPUT_SIZE: tuple[int, int] = (300, 300)

__all__ = ["load_image", "to_tensor"]

PathLike = Union[str, Path]


def load_image(path: PathLike) -> Image.Image:
    """Read an image file from disk and return it as an RGB ``PIL.Image``.

    Args:
        path: filesystem path to a jpg / png / etc. image. Accepts both
            ``str`` and ``pathlib.Path``.

    Returns:
        ``PIL.Image`` in RGB mode (always 3 channels, uint8). Grayscale,
        palette, and RGBA inputs are converted to RGB so downstream
        tensor math never has to handle 1- or 4-channel edge cases.

    Raises:
        FileNotFoundError: if ``path`` does not exist.
        PIL.UnidentifiedImageError: if the file is not a recognized image.
    """
    img = Image.open(path)
    # ``Image.open`` is lazy; ``convert`` forces decode. We force RGB
    # unconditionally so callers can rely on a stable channel count.
    if img.mode != "RGB":
        img = img.convert("RGB")
    return img


def to_tensor(
    img: Image.Image,
    input_size: tuple[int, int] = _DEFAULT_INPUT_SIZE,
) -> torch.Tensor:
    """Convert a ``PIL.Image`` into the tensor SSD300 expects.

    Args:
        img: a ``PIL.Image``. Will be converted to RGB and resized. The
            caller does NOT need to pre-convert modes or pre-resize.
        input_size: ``(height, width)`` of the model input. SSD300 expects
            ``(300, 300)``. Custom sizes are allowed for testing but
            **will not** produce meaningful detections outside the
            model's training resolution.

    Returns:
        ``torch.Tensor`` of shape ``(1, 3, H, W)``, dtype ``float32``,
        ImageNet-normalized. Channel order is RGB. Coordinates are
        scaled to the model's input grid; downstream code (``decode`` /
        ``nms``) is responsible for mapping boxes back to the
        original-image coordinate system.
    """
    # Step 1: bilinear resize. We force RGB first so a 1-channel or
    # 4-channel input cannot silently produce a wrong-shape tensor.
    # Pillow 9.1+ uses the Resampling enum; 12.x is what we have.
    resized = img.convert("RGB").resize(input_size, Image.Resampling.BILINEAR)

    # Steps 2-4 in numpy, which is a transitive dep of torch and faster
    # than per-element Python loops for the broadcasted subtract+divide.
    # ``from_numpy`` is zero-copy, so this stays memory-efficient.
    import numpy as np

    arr = np.asarray(resized, dtype=np.float32) / 255.0                        # HWC, [0,1]
    mean = np.asarray(_IMAGENET_MEAN, dtype=np.float32).reshape(1, 1, 3)        # broadcast over H,W
    std = np.asarray(_IMAGENET_STD, dtype=np.float32).reshape(1, 1, 3)
    arr = (arr - mean) / std                                                    # HWC, normalized
    arr = arr.transpose(2, 0, 1)                                               # HWC -> CHW

    # Step 5: build the (1, 3, H, W) tensor. ``contiguous`` ensures the
    # memory layout matches what downstream torch ops expect — important
    # for torchvision SSD's internal reshape-free indexing.
    tensor = torch.from_numpy(arr).contiguous()
    return tensor.unsqueeze(0)
