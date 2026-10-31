"""Model loader for the SSD300 (VGG16 backbone) inference demo.

Spec coverage
-------------
- FR-2  pretrained SSD300 (VGG16 backbone) on COCO, loaded via torchvision.
- FR-4  inference under ``torch.no_grad()`` (callers wrap; see pipeline.py).

Design notes
------------
The heavy ML stack (``torch``, ``torchvision``) is imported **lazily**
inside :func:`build_model` rather than at module top. This keeps
``import ssd_demo.infer`` cheap and side-effect-free, which matters
because:

1. The host repo is TypeScript/Java — we don't want a stray torchvision
   import to slow down anyone who just opens ``infer.py`` in an editor.
2. Unit tests in ``tests/test_infer.py`` can patch
   ``sys.modules["torchvision.models.detection"]`` to verify the call
   shape without actually installing the ~700 MB torch+torchvision stack.

The caller's contract is:

.. code-block:: python

    model, weights = build_model(device="cpu")
    with torch.no_grad():
        outputs = model([tensors])   # list[dict{boxes, labels, scores}]

``torch.no_grad()`` lives in the caller (per FR-4), not in this module.
That keeps :func:`build_model` composable and matches torchvision's
own convention of leaving gradient management to the caller.
"""
# Copyright (c) 2026 maijun
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

# Copyright (c) 2026 maijun

# Licensed under the MIT License.
# See the LICENSE file in the project root for the full license text.

# Copyright (c) 2026 maijun

# Licensed under the MIT License.
# See the LICENSE file in the project root for the full license text.

# Copyright (c) 2026 maijun

# Licensed under the MIT License.
# See the LICENSE file in the project root for the full license text.

# Copyright (c) 2026 maijun

# Licensed under the MIT License.
# See the LICENSE file in the project root for the full license text.

from __future__ import annotations

from typing import Any

__all__ = ["build_model"]


def build_model(device: str = "cpu") -> tuple[Any, Any]:
    """Construct a pretrained SSD300 model ready for inference.

    Args:
        device: torch device string. ``"cpu"`` is always safe;
            ``"cuda"`` / ``"cuda:0"`` requires CUDA installed and a
            matching torch build. Any value accepted by
            ``torch.nn.Module.to`` is valid.

    Returns:
        A 2-tuple ``(model, weights)``:

        * ``model`` — the SSD300 module in ``eval`` mode, on ``device``.
          Its forward pass returns a ``list[dict]`` of length 1 (one
          entry per image), each with keys ``boxes``, ``labels``,
          ``scores``. Coordinates are in **input-image scale** (xyxy,
          pixels), not normalized — that is why we pair it with our
          preprocessing in :mod:`ssd_demo.preprocess` rather than
          re-scaling here.
        * ``weights`` — the :class:`SSD300_VGG16_Weights` handle
          (the ``DEFAULT`` enum value). Use
          ``weights.meta["categories"]`` for the 91 COCO class names
          (see :mod:`ssd_demo.labels`) and ``weights.meta["input_size"]``
          for the canonical ``(300, 300)`` input.

    Raises:
        ImportError: if ``torchvision`` is not installed.
        RuntimeError: if ``device="cuda"`` is requested but CUDA is
            unavailable (raised by torch, not by us).

    Notes:
        First call on a given machine downloads the pretrained
        checkpoint (~135 MB) into the torchvision-managed cache and
        verifies its integrity; subsequent calls are instant. The
        download happens inside ``ssd300_vgg16(weights=...)`` — we
        do not download anything ourselves in this module.
        Custom caching / sha256 pinning is the responsibility of
        :mod:`ssd_demo.weights` (a separate, future task).
    """
    # Lazy imports: defer the heavy stack until the user actually
    # calls build_model(). Keeps `import ssd_demo.infer` free of side
    # effects and lets tests patch sys.modules without an actual
    # torchvision install.
    from torchvision.models.detection import (
        ssd300_vgg16,
        SSD300_VGG16_Weights,
    )

    weights = SSD300_VGG16_Weights.DEFAULT
    model = ssd300_vgg16(weights=weights)
    model.eval()       # FR-4: inference-time graph mode
    model.to(device)   # FR-4: device-selectable
    return model, weights
