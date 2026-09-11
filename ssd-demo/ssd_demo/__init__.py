"""ssd_demo — a small end-to-end SSD (Single Shot MultiBox Detector) inference demo.

Public modules (filled in across subsequent tasks):
    infer       — build_model(): pretrained SSD300 + Weights handle   (FR-2, FR-4)
    preprocess  — load_image / to_tensor                             (FR-3)
    decode      — box decoding math + Detection dataclass             (FR-5)
    nms         — per-class non-max suppression                       (FR-6)
    draw        — annotate_image()                                   (FR-7)
    weights     — download / verify / cache pretrained weights        (FR-9, NFR-5)
    labels      — COCO class names from Weights.meta["categories"]    (FR-7)
    pipeline    — run(): the public end-to-end function               (FR-1..FR-7)
    __main__    — argparse CLI                                        (FR-8)
"""

__version__ = "0.1.0"
