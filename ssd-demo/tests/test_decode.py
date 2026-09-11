"""Unit tests for :mod:`ssd_demo.decode` — the box decoding + adapter layer (FR-5).

Three groups of tests:
  1. :class:`Detection` dataclass — construction, immutability, equality, repr.
  2. :func:`decode_boxes_demo` — the canonical SSD Eq. 2 math, hand-checked.
  3. :func:`to_detections` — the torchvision-output adapter (scales boxes,
     filters by score and class_id, sorts).

No torchvision dependency — fake model outputs are constructed in-memory.
"""
from __future__ import annotations

import pytest
import torch

from ssd_demo.decode import (
    Detection,
    decode_boxes_demo,
    to_detections,
)


# =============================================================================
# Section 1 — Detection dataclass
# =============================================================================

class TestDetectionDataclass:
    """The dataclass is small but its immutability and equality semantics
    matter to downstream NMS code."""

    def test_construct_with_positional_args(self):
        d = Detection((1.0, 2.0, 3.0, 4.0), 0.9, 12)
        assert d.box == (1.0, 2.0, 3.0, 4.0)
        assert d.score == 0.9
        assert d.class_id == 12

    def test_construct_with_keyword_args(self):
        d = Detection(box=(0, 0, 10, 10), score=0.5, class_id=1)
        assert d.box == (0, 0, 10, 10)
        assert d.score == 0.5
        assert d.class_id == 1

    def test_is_frozen_assignment_raises(self):
        """frozen=True dataclasses reject setattr — NMS relies on this
        to use Detections as dict keys / set members."""
        d = Detection((0, 0, 10, 10), 0.5, 1)
        with pytest.raises(Exception):  # FrozenInstanceError, but Exception is portable
            d.score = 0.9  # type: ignore[misc]

    def test_is_hashable(self):
        """frozen=True implies __hash__; downstream code may put
        Detections in sets or use them as dict keys."""
        a = Detection((0, 0, 10, 10), 0.5, 1)
        b = Detection((0, 0, 10, 10), 0.5, 1)
        c = Detection((0, 0, 10, 10), 0.5, 2)  # different class_id
        assert hash(a) == hash(b)
        assert hash(a) != hash(c)
        # Usable in a set
        assert len({a, b, c}) == 2

    def test_equality(self):
        a = Detection((0, 0, 10, 10), 0.5, 1)
        b = Detection((0, 0, 10, 10), 0.5, 1)
        c = Detection((0, 0, 10, 10), 0.5, 2)
        assert a == b
        assert a != c

    def test_repr_includes_all_fields(self):
        """repr should be informative for debug logs."""
        d = Detection((1.0, 2.0, 3.0, 4.0), 0.9, 12)
        text = repr(d)
        assert "1.0" in text and "2.0" in text and "3.0" in text and "4.0" in text
        assert "0.9" in text
        assert "12" in text


# =============================================================================
# Section 2 — decode_boxes_demo (SSD Eq. 2 reference implementation)
# =============================================================================

class TestDecodeBoxesDemo:
    """Hand-check the canonical SSD box-decoding formula. The math is
    small enough that we can verify every branch with closed-form
    arithmetic — no statistical assertions needed."""

    def test_zero_offsets_yields_prior_box_in_xyxy(self):
        """When (dx, dy, dw, dh) are all zero, the decoded box should
        be exactly the prior converted from (cx, cy, w, h) → xyxy."""
        cx = torch.tensor([150.0])
        cy = torch.tensor([150.0])
        w = torch.tensor([20.0])
        h = torch.tensor([10.0])
        offsets = torch.zeros(1, 4)
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        # Prior (150,150,20,10) → xyxy (140, 145, 160, 155)
        expected = torch.tensor([[140.0, 145.0, 160.0, 155.0]])
        assert torch.allclose(boxes, expected)

    def test_positive_dx_shifts_box_right(self):
        """dx=1.0 with prior w=20 → x_center shifts right by 0.1*1.0*20 = 2.0."""
        cx = torch.tensor([100.0])
        cy = torch.tensor([100.0])
        w = torch.tensor([20.0])
        h = torch.tensor([20.0])
        offsets = torch.tensor([[1.0, 0.0, 0.0, 0.0]])
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        # decoded_cx = 100 + 0.1*1.0*20 = 102; size unchanged; xyxy = (92,90,112,110)
        expected = torch.tensor([[92.0, 90.0, 112.0, 110.0]])
        assert torch.allclose(boxes, expected)

    def test_negative_dx_shifts_box_left(self):
        cx = torch.tensor([100.0])
        cy = torch.tensor([100.0])
        w = torch.tensor([20.0])
        h = torch.tensor([20.0])
        offsets = torch.tensor([[-1.0, 0.0, 0.0, 0.0]])
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        # decoded_cx = 100 + 0.1*(-1.0)*20 = 98; xyxy = (88,90,108,110)
        expected = torch.tensor([[88.0, 90.0, 108.0, 110.0]])
        assert torch.allclose(boxes, expected)

    def test_positive_dy_shifts_box_down(self):
        """Positive dy → larger y_center → lower y1, larger y2."""
        cx = torch.tensor([100.0])
        cy = torch.tensor([100.0])
        w = torch.tensor([20.0])
        h = torch.tensor([20.0])
        offsets = torch.tensor([[0.0, 1.0, 0.0, 0.0]])
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        # decoded_cy = 100 + 2 = 102; xyxy = (90, 92, 110, 112)
        expected = torch.tensor([[90.0, 92.0, 110.0, 112.0]])
        assert torch.allclose(boxes, expected)

    def test_positive_dw_widens_box(self):
        """dw=1.0 multiplies w by exp(0.2) ≈ 1.2214; h unchanged since dh=0."""
        cx = torch.tensor([100.0])
        cy = torch.tensor([100.0])
        w = torch.tensor([20.0])
        h = torch.tensor([20.0])
        offsets = torch.tensor([[0.0, 0.0, 1.0, 0.0]])
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        # Only w is scaled (dw=1). h stays at 20. Center stays at (100,100).
        # xyxy = (100 - 12.214, 100 - 10, 100 + 12.214, 100 + 10)
        import math
        expected_w = 20.0 * math.exp(0.2)
        expected = torch.tensor([
            [100.0 - expected_w / 2, 100.0 - 10.0,
             100.0 + expected_w / 2, 100.0 + 10.0]
        ])
        assert torch.allclose(boxes, expected, atol=1e-5)

    def test_positive_dh_tallens_box(self):
        """dh=1.0 multiplies h by exp(0.2); w unchanged since dw=0."""
        cx = torch.tensor([100.0])
        cy = torch.tensor([100.0])
        w = torch.tensor([20.0])
        h = torch.tensor([20.0])
        offsets = torch.tensor([[0.0, 0.0, 0.0, 1.0]])
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        # Only h is scaled (dh=1). w stays at 20. Center stays at (100,100).
        # xyxy = (100 - 10, 100 - 12.214, 100 + 10, 100 + 12.214)
        import math
        expected_h = 20.0 * math.exp(0.2)
        expected = torch.tensor([
            [100.0 - 10.0, 100.0 - expected_h / 2,
             100.0 + 10.0, 100.0 + expected_h / 2]
        ])
        assert torch.allclose(boxes, expected, atol=1e-5)

    def test_combined_offsets_hand_computed(self):
        """All four offsets non-zero — verify against hand arithmetic."""
        cx = torch.tensor([100.0])
        cy = torch.tensor([200.0])
        w = torch.tensor([20.0])
        h = torch.tensor([10.0])
        # Hand-compute expected:
        # decoded_cx = 100 + 0.1 * 1.0 * 20 = 102
        # decoded_cy = 200 + 0.1 * 0.0 * 10 = 200
        # decoded_w = 20 * exp(0) = 20
        # decoded_h = 10 * exp(0) = 10
        # xyxy = (92, 195, 112, 205)
        offsets = torch.tensor([[1.0, 0.0, 0.0, 0.0]])
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        expected = torch.tensor([[92.0, 195.0, 112.0, 205.0]])
        assert torch.allclose(boxes, expected)

    def test_batch_n_boxes(self):
        """Vectorized over N=3 boxes."""
        cx = torch.tensor([10.0, 20.0, 30.0])
        cy = torch.tensor([40.0, 50.0, 60.0])
        w = torch.tensor([4.0, 6.0, 8.0])
        h = torch.tensor([2.0, 2.0, 2.0])
        offsets = torch.zeros(3, 4)
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        assert boxes.shape == (3, 4)
        # Verify each: (cx-w/2, cy-h/2, cx+w/2, cy+h/2)
        expected = torch.tensor([
            [10.0 - 2.0, 40.0 - 1.0, 10.0 + 2.0, 40.0 + 1.0],
            [20.0 - 3.0, 50.0 - 1.0, 20.0 + 3.0, 50.0 + 1.0],
            [30.0 - 4.0, 60.0 - 1.0, 30.0 + 4.0, 60.0 + 1.0],
        ])
        assert torch.allclose(boxes, expected)

    def test_clip_bounds_applied_to_output(self):
        """When clip_bounds=(0, 300) is given, output coords are clamped."""
        cx = torch.tensor([-50.0])     # negative → would decode to negative coords
        cy = torch.tensor([1000.0])    # way beyond 300
        w = torch.tensor([20.0])
        h = torch.tensor([20.0])
        offsets = torch.zeros(1, 4)
        boxes = decode_boxes_demo(cx, cy, w, h, offsets, clip_bounds=(0.0, 300.0))
        # Everything clamps to [0, 300].
        assert boxes.min().item() == 0.0
        assert boxes.max().item() == 300.0

    def test_no_clip_by_default(self):
        """Without clip_bounds, output is NOT clamped — sanity check
        that the default is unclamped."""
        cx = torch.tensor([-50.0])
        cy = torch.tensor([1000.0])
        w = torch.tensor([20.0])
        h = torch.tensor([20.0])
        offsets = torch.zeros(1, 4)
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        # Not all values are in [0, 300] — no clamping happened.
        assert boxes.min().item() < 0.0
        assert boxes.max().item() > 300.0

    def test_wrong_offsets_shape_raises(self):
        cx = torch.tensor([100.0])
        cy = torch.tensor([100.0])
        w = torch.tensor([20.0])
        h = torch.tensor([20.0])
        # Last dim must be 4
        with pytest.raises(ValueError, match="offsets must have shape"):
            decode_boxes_demo(cx, cy, w, h, torch.zeros(3, 3))

    def test_wrong_rank_offsets_raises(self):
        cx = torch.tensor([100.0])
        cy = torch.tensor([100.0])
        w = torch.tensor([20.0])
        h = torch.tensor([20.0])
        with pytest.raises(ValueError, match="offsets must have shape"):
            decode_boxes_demo(cx, cy, w, h, torch.zeros(3))  # 1-D, wrong rank

    def test_wrong_rank_for_prior_raises(self):
        """cx/cy/w/h must be 1-D."""
        offsets = torch.zeros(1, 4)
        with pytest.raises(ValueError, match="cx must be 1-D"):
            decode_boxes_demo(
                torch.tensor([[100.0]]),  # 2-D
                torch.tensor([100.0]),
                torch.tensor([20.0]),
                torch.tensor([20.0]),
                offsets,
            )

    def test_length_mismatch_raises(self):
        """cx/cy/w/h must have same length as offsets."""
        offsets = torch.zeros(3, 4)
        with pytest.raises(ValueError, match="cx length"):
            decode_boxes_demo(
                torch.tensor([100.0, 200.0]),  # length 2 vs offsets length 3
                torch.tensor([100.0, 200.0, 300.0]),
                torch.tensor([20.0, 20.0, 20.0]),
                torch.tensor([20.0, 20.0, 20.0]),
                offsets,
            )

    def test_works_with_float64(self):
        """Dtype should be preserved / cast to the natural torch behavior."""
        cx = torch.tensor([100.0], dtype=torch.float64)
        cy = torch.tensor([100.0], dtype=torch.float64)
        w = torch.tensor([20.0], dtype=torch.float64)
        h = torch.tensor([20.0], dtype=torch.float64)
        offsets = torch.zeros(1, 4, dtype=torch.float64)
        boxes = decode_boxes_demo(cx, cy, w, h, offsets)
        assert boxes.dtype == torch.float64
        assert torch.allclose(boxes, torch.tensor([[90.0, 90.0, 110.0, 110.0]], dtype=torch.float64))


# =============================================================================
# Section 3 — to_detections (torchvision-output adapter)
# =============================================================================

def _fake_model_output(
    boxes: list[list[float]],
    scores: list[float],
    labels: list[int],
) -> list[dict[str, torch.Tensor]]:
    """Build a torchvision-style ``list[{boxes, labels, scores}]`` with
    one batch element. Boxes are in 300×300 input-image coords."""
    return [{
        "boxes": torch.tensor(boxes, dtype=torch.float32),
        "scores": torch.tensor(scores, dtype=torch.float32),
        "labels": torch.tensor(labels, dtype=torch.int64),
    }]


class TestToDetections:
    """The adapter handles four jobs: scale boxes, filter background,
    filter by score, sort by score desc."""

    def test_empty_input_returns_empty_list(self):
        assert to_detections([], orig_size=(300, 300)) == []
        assert to_detections([], orig_size=(1024, 768), score_threshold=0.5) == []

    def test_single_detection_passes_through_unchanged_size(self):
        """300×300 input image → boxes stay as-is."""
        out = _fake_model_output(
            boxes=[[10.0, 20.0, 110.0, 220.0]],
            scores=[0.9],
            labels=[1],
        )
        dets = to_detections(out, orig_size=(300, 300))
        assert len(dets) == 1
        d = dets[0]
        assert d.box == (10.0, 20.0, 110.0, 220.0)
        assert d.score == pytest.approx(0.9, abs=1e-6)
        assert d.class_id == 1

    def test_scales_boxes_to_original_image(self):
        """600×400 input image → all box coords scaled by 2.0 in x, 1.333… in y."""
        out = _fake_model_output(
            boxes=[[10.0, 20.0, 110.0, 220.0]],
            scores=[0.9],
            labels=[1],
        )
        dets = to_detections(out, orig_size=(600, 400))
        assert len(dets) == 1
        # x1: 10 * (600/300) = 20; x2: 110 * 2 = 220
        # y1: 20 * (400/300) = 26.667; y2: 220 * 1.333 = 293.333
        assert dets[0].box[0] == pytest.approx(20.0, abs=1e-4)
        assert dets[0].box[1] == pytest.approx(26.6667, abs=1e-3)
        assert dets[0].box[2] == pytest.approx(220.0, abs=1e-4)
        assert dets[0].box[3] == pytest.approx(293.3333, abs=1e-3)

    def test_score_threshold_filters(self):
        out = _fake_model_output(
            boxes=[[10, 20, 110, 220], [50, 60, 150, 260], [70, 80, 170, 280]],
            scores=[0.9, 0.3, 0.7],
            labels=[1, 2, 3],
        )
        dets = to_detections(out, orig_size=(300, 300), score_threshold=0.5)
        # 0.9 and 0.7 pass; 0.3 dropped.
        assert len(dets) == 2
        assert [d.score for d in dets] == pytest.approx([0.9, 0.7])

    def test_score_threshold_zero_keeps_all(self):
        out = _fake_model_output(
            boxes=[[10, 20, 110, 220], [50, 60, 150, 260]],
            scores=[0.001, 0.0001],
            labels=[1, 2],
        )
        dets = to_detections(out, orig_size=(300, 300), score_threshold=0.0)
        assert len(dets) == 2

    def test_class_id_zero_filtered(self):
        """Defensive: even if torchvision ever passes background (label 0)
        through, we drop it."""
        out = _fake_model_output(
            boxes=[[10, 20, 110, 220], [50, 60, 150, 260]],
            scores=[0.9, 0.8],
            labels=[0, 1],   # 0 = background; should be dropped
        )
        dets = to_detections(out, orig_size=(300, 300))
        assert len(dets) == 1
        assert dets[0].class_id == 1

    def test_real_coco_class_ids_pass_through(self):
        """Labels 1..91 all pass; we don't apply any whitelist."""
        out = _fake_model_output(
            boxes=[[10, 20, 110, 220]] * 5,
            scores=[0.9, 0.8, 0.7, 0.6, 0.5],
            labels=[1, 17, 67, 88, 91],  # person, dog, dining table, teddy bear, toothbrush
        )
        dets = to_detections(out, orig_size=(300, 300))
        assert len(dets) == 5
        assert [d.class_id for d in dets] == [1, 17, 67, 88, 91]

    def test_output_sorted_by_score_descending(self):
        """The caller wants `detections[:top_k]` to give the top-k by score.
        Verify by passing scores in scrambled order."""
        out = _fake_model_output(
            boxes=[[10, 20, 110, 220], [30, 40, 130, 240], [50, 60, 150, 260], [70, 80, 170, 280]],
            scores=[0.5, 0.9, 0.3, 0.7],
            labels=[1, 2, 3, 4],
        )
        dets = to_detections(out, orig_size=(300, 300))
        assert [d.score for d in dets] == pytest.approx([0.9, 0.7, 0.5, 0.3])

    def test_batch_size_2_flattens_into_one_list(self):
        """Two images' detections end up in one flat list, sorted."""
        batch = [
            {
                "boxes": torch.tensor([[10.0, 20.0, 110.0, 220.0]], dtype=torch.float32),
                "scores": torch.tensor([0.5], dtype=torch.float32),
                "labels": torch.tensor([1], dtype=torch.int64),
            },
            {
                "boxes": torch.tensor([[30.0, 40.0, 130.0, 240.0]], dtype=torch.float32),
                "scores": torch.tensor([0.9], dtype=torch.float32),
                "labels": torch.tensor([2], dtype=torch.int64),
            },
        ]
        dets = to_detections(batch, orig_size=(300, 300))
        assert len(dets) == 2
        assert [d.score for d in dets] == pytest.approx([0.9, 0.5])
        assert dets[0].class_id == 2
        assert dets[1].class_id == 1

    def test_empty_per_image_dict_skipped(self):
        """If a batch element has 0 boxes (e.g., a featureless image),
        it contributes nothing to the output."""
        batch = [
            {
                "boxes": torch.tensor([[10.0, 20.0, 110.0, 220.0]], dtype=torch.float32),
                "scores": torch.tensor([0.5], dtype=torch.float32),
                "labels": torch.tensor([1], dtype=torch.int64),
            },
            {
                "boxes": torch.zeros(0, 4, dtype=torch.float32),
                "scores": torch.zeros(0, dtype=torch.float32),
                "labels": torch.zeros(0, dtype=torch.int64),
            },
        ]
        dets = to_detections(batch, orig_size=(300, 300))
        assert len(dets) == 1
        assert dets[0].class_id == 1

    def test_degenerate_box_filtered(self):
        """Boxes where x2 ≤ x1 or y2 ≤ y1 are degenerate; we drop them
        rather than let NMS fight with zero-area boxes."""
        out = _fake_model_output(
            boxes=[[10, 20, 10, 20], [30, 40, 130, 240]],  # first has x2 == x1
            scores=[0.9, 0.8],
            labels=[1, 2],
        )
        dets = to_detections(out, orig_size=(300, 300))
        assert len(dets) == 1
        assert dets[0].class_id == 2

    def test_score_threshold_combined_with_sort(self):
        """Threshold filters AND remaining list is sorted."""
        out = _fake_model_output(
            boxes=[[0, 0, 10, 10]] * 5,
            scores=[0.1, 0.9, 0.4, 0.7, 0.5],
            labels=[1, 2, 3, 4, 5],
        )
        dets = to_detections(out, orig_size=(300, 300), score_threshold=0.5)
        # Below threshold (0.1, 0.4) dropped; survivors (0.9, 0.7, 0.5) sorted desc.
        assert [d.score for d in dets] == pytest.approx([0.9, 0.7, 0.5])

    def test_non_uniform_scaling_correct(self):
        """Tall vs wide input images scale differently per axis."""
        out = _fake_model_output(
            boxes=[[100.0, 50.0, 200.0, 150.0]],  # 100×100 box at center of 300×300
            scores=[0.9],
            labels=[1],
        )
        dets = to_detections(out, orig_size=(600, 300))  # x:2x, y:1x
        # Expected: (200, 50, 400, 150) — exactly the input image stretched 2x in x.
        assert dets[0].box == pytest.approx((200.0, 50.0, 400.0, 150.0), abs=1e-4)
