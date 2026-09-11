"""Unit tests for :mod:`ssd_demo.preprocess` — image preprocessing (FR-3).

Unlike ``test_infer.py``, these tests run against the **real** torch and
Pillow stacks (both verified installed in Phase 2). No mocking. We
construct images programmatically with ``PIL.Image.new`` so there are
no binary fixtures to commit; the only on-disk I/O uses pytest's
``tmp_path`` fixture for the ``load_image`` tests.
"""
from __future__ import annotations

import math
from pathlib import Path

import pytest
import torch
from PIL import Image


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _save_rgb(path: Path, color: tuple[int, int, int] = (255, 0, 0), size=(10, 10)) -> Path:
    """Write a solid-colour RGB image to disk and return the path."""
    Image.new("RGB", size, color).save(path)
    return path


def _save_gray(path: Path, value: int = 128, size=(10, 10)) -> Path:
    """Write a single-channel (mode='L') grayscale image to disk."""
    Image.new("L", size, value).save(path)
    return path


def _save_rgba(path: Path, color: tuple[int, int, int, int] = (255, 0, 0, 128), size=(10, 10)) -> Path:
    """Write a 4-channel RGBA image to disk."""
    Image.new("RGBA", size, color).save(path)
    return path


# ---------------------------------------------------------------------------
# load_image — public surface
# ---------------------------------------------------------------------------

def test_load_image_module_exports(tmp_path: Path) -> None:
    """``load_image`` is exported from ``ssd_demo.preprocess``."""
    from ssd_demo.preprocess import load_image
    assert callable(load_image)


def test_load_image_returns_rgb_pil_image(tmp_path: Path) -> None:
    """An RGB file round-trips as a 3-channel PIL.Image."""
    from ssd_demo.preprocess import load_image
    p = _save_rgb(tmp_path / "red.png", (255, 0, 0))
    img = load_image(p)
    assert isinstance(img, Image.Image)
    assert img.mode == "RGB"
    assert img.size == (10, 10)


def test_load_image_accepts_string_path(tmp_path: Path) -> None:
    """Path can be a plain str, not just pathlib.Path."""
    from ssd_demo.preprocess import load_image
    p = _save_rgb(tmp_path / "x.png")
    img = load_image(str(p))
    assert img.mode == "RGB"


def test_load_image_accepts_pathlib_path(tmp_path: Path) -> None:
    """Path can be a pathlib.Path."""
    from ssd_demo.preprocess import load_image
    p = _save_rgb(tmp_path / "x.png")
    img = load_image(Path(p))
    assert img.mode == "RGB"


def test_load_image_converts_grayscale_to_rgb(tmp_path: Path) -> None:
    """A mode='L' image is converted to RGB so downstream math sees 3 channels."""
    from ssd_demo.preprocess import load_image
    p = _save_gray(tmp_path / "gray.png", value=128)
    img = load_image(p)
    assert img.mode == "RGB"
    # A grayscale 128 image has equal R/G/B after conversion.
    assert img.getpixel((0, 0)) == (128, 128, 128)


def test_load_image_converts_rgba_to_rgb(tmp_path: Path) -> None:
    """A mode='RGBA' image is converted to RGB (alpha dropped)."""
    from ssd_demo.preprocess import load_image
    p = _save_rgba(tmp_path / "rgba.png", (10, 20, 30, 255))
    img = load_image(p)
    assert img.mode == "RGB"
    assert img.getpixel((0, 0)) == (10, 20, 30)


def test_load_image_missing_file_raises(tmp_path: Path) -> None:
    """A nonexistent path raises FileNotFoundError."""
    from ssd_demo.preprocess import load_image
    with pytest.raises(FileNotFoundError):
        load_image(tmp_path / "does-not-exist.png")


def test_load_image_garbage_file_raises(tmp_path: Path) -> None:
    """A non-image file raises PIL.UnidentifiedImageError."""
    from ssd_demo.preprocess import load_image
    bad = tmp_path / "garbage.png"
    bad.write_bytes(b"this is not an image file")
    with pytest.raises(Image.UnidentifiedImageError):
        load_image(bad)


# ---------------------------------------------------------------------------
# to_tensor — output shape / dtype
# ---------------------------------------------------------------------------

def test_to_tensor_module_exports() -> None:
    from ssd_demo.preprocess import to_tensor
    assert callable(to_tensor)


def test_to_tensor_default_input_size_is_300() -> None:
    """Spec FR-3: default resize target is 300×300."""
    from ssd_demo.preprocess import to_tensor
    img = Image.new("RGB", (600, 400), (128, 128, 128))
    out = to_tensor(img)
    assert out.shape == (1, 3, 300, 300)


def test_to_tensor_output_is_four_dim_batch_chw() -> None:
    """Output is always (batch=1, channels=3, H, W)."""
    from ssd_demo.preprocess import to_tensor
    img = Image.new("RGB", (50, 50), (10, 20, 30))
    out = to_tensor(img)
    assert out.ndim == 4
    assert out.shape[0] == 1
    assert out.shape[1] == 3


def test_to_tensor_dtype_is_float32() -> None:
    """Output dtype is float32 — what SSD300's conv stack expects."""
    from ssd_demo.preprocess import to_tensor
    img = Image.new("RGB", (10, 10), (128, 128, 128))
    out = to_tensor(img)
    assert out.dtype == torch.float32


def test_to_tensor_resizes_input_to_300x300() -> None:
    """A non-square input is stretched to 300×300 (no aspect preservation)."""
    from ssd_demo.preprocess import to_tensor
    img = Image.new("RGB", (600, 400), (128, 128, 128))
    out = to_tensor(img)
    assert out.shape[-2] == 300
    assert out.shape[-1] == 300


def test_to_tensor_custom_input_size() -> None:
    """Caller can override the resize target (used by tests, not the demo)."""
    from ssd_demo.preprocess import to_tensor
    img = Image.new("RGB", (100, 100), (128, 128, 128))
    out = to_tensor(img, input_size=(224, 224))
    assert out.shape == (1, 3, 224, 224)


def test_to_tensor_forces_rgb_input() -> None:
    """A grayscale PIL image passed directly to to_tensor still produces 3 channels."""
    from ssd_demo.preprocess import to_tensor
    gray = Image.new("L", (50, 50), 200)
    out = to_tensor(gray)
    assert out.shape == (1, 3, 300, 300)


# ---------------------------------------------------------------------------
# to_tensor — normalization math (closed-form, not statistical)
# ---------------------------------------------------------------------------

def test_to_tensor_red_image_produces_known_channel_means() -> None:
    """For solid red (255, 0, 0) the post-normalization per-channel
    means must match the ImageNet formula:

        c0 = (1.000 - 0.485) / 0.229
        c1 = (0.000 - 0.456) / 0.224
        c2 = (0.000 - 0.406) / 0.225

    This is the strongest possible correctness check — it exercises the
    full pipeline (resize / scale / permute / normalize) against a
    hand-computable expected output.
    """
    from ssd_demo.preprocess import to_tensor
    red = Image.new("RGB", (10, 10), (255, 0, 0))
    out = to_tensor(red)

    # Mean across (batch, H, W) → shape (3,)
    means = out.mean(dim=(0, 2, 3)).tolist()

    expected = [
        (1.0 - 0.485) / 0.229,
        (0.0 - 0.456) / 0.224,
        (0.0 - 0.406) / 0.225,
    ]

    for got, exp in zip(means, expected):
        assert abs(got - exp) < 1e-4, f"channel mean {got} != expected {exp}"


def test_to_tensor_white_image_produces_known_channel_means() -> None:
    """For solid white (255, 255, 255) every channel is at its maximum
    positive value (1.0 − mean) / std."""
    from ssd_demo.preprocess import to_tensor
    white = Image.new("RGB", (10, 10), (255, 255, 255))
    out = to_tensor(white)
    means = out.mean(dim=(0, 2, 3)).tolist()
    expected = [(1.0 - m) / s for m, s in zip((0.485, 0.456, 0.406), (0.229, 0.224, 0.225))]
    for got, exp in zip(means, expected):
        assert abs(got - exp) < 1e-4


def test_to_tensor_black_image_produces_known_channel_means() -> None:
    """For solid black (0, 0, 0) every channel is at its minimum
    negative value (0 − mean) / std."""
    from ssd_demo.preprocess import to_tensor
    black = Image.new("RGB", (10, 10), (0, 0, 0))
    out = to_tensor(black)
    means = out.mean(dim=(0, 2, 3)).tolist()
    expected = [(0.0 - m) / s for m, s in zip((0.485, 0.456, 0.406), (0.229, 0.224, 0.225))]
    for got, exp in zip(means, expected):
        assert abs(got - exp) < 1e-4


def test_to_tensor_values_are_finite() -> None:
    """No NaN or Inf in the output regardless of input."""
    from ssd_demo.preprocess import to_tensor
    img = Image.new("RGB", (640, 480), (123, 45, 200))
    out = to_tensor(img)
    assert torch.isfinite(out).all()


def test_to_tensor_red_channel_strictly_higher_than_other_channels() -> None:
    """Sanity check on the RGB axis: a pure-red image must have channel 0
    (R) much greater than channels 1, 2 (G, B)."""
    from ssd_demo.preprocess import to_tensor
    red = Image.new("RGB", (50, 50), (255, 0, 0))
    out = to_tensor(red)
    means = out.mean(dim=(0, 2, 3)).tolist()
    r_mean, g_mean, b_mean = means
    assert r_mean > 2.0, f"red channel mean should be > 2.0, got {r_mean}"
    assert g_mean < -1.5, f"green channel mean should be < -1.5, got {g_mean}"
    assert b_mean < -1.5, f"blue channel mean should be < -1.5, got {b_mean}"


def test_to_tensor_output_range_bounded_by_image_extremes() -> None:
    """After normalization, channel values lie in the closed interval
    [min_norm, max_norm] where min_norm = (0−mean)/std and max_norm = (1−mean)/std.

    For an input that spans the full [0, 255] range, this gives a tight
    bound; we test with a checkerboard to exercise both extremes.
    """
    from ssd_demo.preprocess import to_tensor
    pattern = Image.new("RGB", (20, 20))
    pattern.putpixel((0, 0), (0, 0, 0))
    pattern.putpixel((0, 1), (255, 255, 255))
    pattern.putpixel((1, 0), (255, 0, 0))
    pattern.putpixel((1, 1), (0, 255, 0))
    out = to_tensor(pattern)

    # Compute per-channel expected bounds
    mins = [(0.0 - m) / s for m, s in zip((0.485, 0.456, 0.406), (0.229, 0.224, 0.225))]
    maxs = [(1.0 - m) / s for m, s in zip((0.485, 0.456, 0.406), (0.229, 0.224, 0.225))]

    for c in range(3):
        lo = out[0, c].min().item()
        hi = out[0, c].max().item()
        # Tolerance for float32 arithmetic rounding
        assert lo >= mins[c] - 1e-5, f"channel {c} min {lo} below bound {mins[c]}"
        assert hi <= maxs[c] + 1e-5, f"channel {c} max {hi} above bound {maxs[c]}"


# ---------------------------------------------------------------------------
# to_tensor — dtypes and contiguity (interoperability with downstream torch ops)
# ---------------------------------------------------------------------------

def test_to_tensor_returns_contiguous_memory() -> None:
    """torchvision SSD internally reshapes the input; non-contiguous
    memory forces a copy. We assert contiguity to keep the pipeline
    copy-free."""
    from ssd_demo.preprocess import to_tensor
    img = Image.new("RGB", (50, 50), (50, 100, 150))
    out = to_tensor(img)
    assert out.is_contiguous()


def test_to_tensor_supports_torch_no_grad_pattern() -> None:
    """The output tensor must be usable inside ``torch.no_grad()`` —
    i.e. it must not require_grad (which a fresh tensor never does,
    but worth pinning down as part of the FR-4 contract)."""
    from ssd_demo.preprocess import to_tensor
    img = Image.new("RGB", (10, 10), (0, 0, 0))
    out = to_tensor(img)
    assert out.requires_grad is False
    with torch.no_grad():
        # Just touching it under no_grad would raise if something were
        # wrong; we do a trivial op to be explicit.
        _ = out + 0.0
