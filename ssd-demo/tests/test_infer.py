"""Unit tests for :mod:`ssd_demo.infer` — the model loader (FR-2, FR-4).

These tests verify the call shape of :func:`ssd_demo.infer.build_model`
**without** requiring the ~700 MB torch+torchvision stack to be
installed. They patch ``sys.modules["torchvision.*"]`` so the lazy
``from torchvision.models.detection import ...`` inside ``build_model``
resolves to a fake.

The authoritative end-to-end check that the model actually loads and
runs lives in ``tests/test_pipeline_smoke.py`` (a separate task).
"""
from __future__ import annotations

import inspect
import sys
from unittest.mock import MagicMock, patch

import pytest


# ---------------------------------------------------------------------------
# Signature / API surface tests (no mocking needed)
# ---------------------------------------------------------------------------

def test_build_model_is_callable() -> None:
    """The public symbol exists and is callable."""
    from ssd_demo.infer import build_model
    assert callable(build_model)


def test_build_model_is_in_dunder_all() -> None:
    """Public API hygiene — the function is explicitly exported."""
    import ssd_demo.infer as infer_mod
    assert "build_model" in infer_mod.__all__


def test_build_model_signature_has_device_defaulting_to_cpu() -> None:
    """``device`` parameter present, default ``"cpu"``."""
    from ssd_demo.infer import build_model
    sig = inspect.signature(build_model)
    assert "device" in sig.parameters
    assert sig.parameters["device"].default == "cpu"


def test_module_docstring_cites_fr2_fr4() -> None:
    """Module docstring must reference FR-2 (model) and FR-4 (no_grad),
    so a reader can map code → spec without grepping the spec.md."""
    import ssd_demo.infer as infer_mod
    assert infer_mod.__doc__ is not None
    assert "FR-2" in infer_mod.__doc__
    assert "FR-4" in infer_mod.__doc__


# ---------------------------------------------------------------------------
# Behavioural tests with sys.modules patching
# ---------------------------------------------------------------------------

def _patch_torchvision_detection():
    """Build a fake torchvision.models.detection module + its parents
    and install them into sys.modules so the lazy import inside
    ``build_model`` resolves to the fake.

    Returns a tuple ``(fake_detection_mod, fake_model, fake_weights_handle)``.
    """
    fake_model = MagicMock(name="fake_ssd300_model")
    fake_weights_handle = MagicMock(name="fake_weights_handle")

    fake_detection_mod = MagicMock(name="torchvision.models.detection")
    fake_detection_mod.ssd300_vgg16 = MagicMock(return_value=fake_model)
    # The DEFAULT class attribute must be set *before* build_model runs.
    fake_detection_mod.SSD300_VGG16_Weights.DEFAULT = fake_weights_handle

    fake_torchvision_models = MagicMock(name="torchvision.models")
    fake_torchvision_models.detection = fake_detection_mod

    fake_torchvision = MagicMock(name="torchvision")
    fake_torchvision.models = fake_torchvision_models

    return fake_torchvision, fake_torchvision_models, fake_detection_mod, fake_model, fake_weights_handle


def _modules_to_patch(fake_torchvision, fake_torchvision_models, fake_detection_mod):
    return {
        "torchvision": fake_torchvision,
        "torchvision.models": fake_torchvision_models,
        "torchvision.models.detection": fake_detection_mod,
    }


def test_build_model_uses_default_weights_handle() -> None:
    """``SSD300_VGG16_Weights.DEFAULT`` is the handle passed to
    ``ssd300_vgg16``."""
    from ssd_demo import infer as infer_mod

    f_tv, f_tvm, f_det, f_model, f_w = _patch_torchvision_detection()

    with patch.dict(sys.modules, _modules_to_patch(f_tv, f_tvm, f_det)):
        model, weights = infer_mod.build_model(device="cpu")

    f_det.ssd300_vgg16.assert_called_once_with(weights=f_w)
    assert model is f_model
    assert weights is f_w


def test_build_model_puts_model_in_eval_mode() -> None:
    """FR-4: the returned model must be in ``eval()`` mode."""
    from ssd_demo import infer as infer_mod

    f_tv, f_tvm, f_det, f_model, _ = _patch_torchvision_detection()

    with patch.dict(sys.modules, _modules_to_patch(f_tv, f_tvm, f_det)):
        infer_mod.build_model(device="cpu")

    f_model.eval.assert_called_once()


def test_build_model_moves_model_to_requested_device() -> None:
    """FR-4: ``model.to(device)`` is called with the user-supplied device."""
    from ssd_demo import infer as infer_mod

    f_tv, f_tvm, f_det, f_model, _ = _patch_torchvision_detection()

    with patch.dict(sys.modules, _modules_to_patch(f_tv, f_tvm, f_det)):
        infer_mod.build_model(device="cuda:0")

    f_model.to.assert_called_once_with("cuda:0")


def test_build_model_default_device_is_cpu() -> None:
    """Omitting ``device`` must default to ``"cpu"``."""
    from ssd_demo import infer as infer_mod

    f_tv, f_tvm, f_det, f_model, _ = _patch_torchvision_detection()

    with patch.dict(sys.modules, _modules_to_patch(f_tv, f_tvm, f_det)):
        infer_mod.build_model()

    f_model.to.assert_called_once_with("cpu")


def test_build_model_call_order_eval_then_to() -> None:
    """The canonical sequence is ``ssd300_vgg16(weights=...)`` → ``.eval()``
    → ``.to(device)``. Verify ordering because some torch modules are
    sensitive to mode transitions *after* moving to a different device."""
    from ssd_demo import infer as infer_mod

    f_tv, f_tvm, f_det, f_model, _ = _patch_torchvision_detection()
    call_log: list[str] = []

    def record_eval():
        call_log.append("eval")

    def record_to(_device):
        call_log.append("to")

    f_model.eval.side_effect = record_eval
    f_model.to.side_effect = record_to

    with patch.dict(sys.modules, _modules_to_patch(f_tv, f_tvm, f_det)):
        infer_mod.build_model(device="cpu")

    assert call_log == ["eval", "to"]
