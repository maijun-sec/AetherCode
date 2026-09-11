# SSD Demo

> 独立的 **SSD (Single Shot MultiBox Detector) 目标检测** 推理 demo,Python + PyTorch 实现。
> 跟主仓编译链解耦,作为 VLM 工具 + tool_call RPC 的**真实端到端验证**。
>
> **关键代码**: `ssd-demo/` (Python 3.10+)
>
> **关键 round**: R250+ (作为发布前最终验证, R237 final release 之前必跑)

---

## 1. 是什么 / 不是

**是**:
- 真实可跑的 PyTorch SSD 目标检测 demo (依赖 `torch` + `torchvision` + `pillow` + `numpy`)
- 用 torchvision 内置的预训练 SSD300 + VGG16 backbone
- 跑 `python -m ssd_demo --input image.jpg` 检出物体
- CI 端到端冒烟测试 (验证 daemon ↔ image_understand tool ↔ Python inference 整条链)

**不是**:
- 不是 aethercode 内部 agent 演示
- 不参与 Java 编译链 (`mvn` 不动它)
- 不需要 LLM 就能跑(纯 CV)

---

## 2. 跑通 (5 分钟)

```bash
cd ssd-demo
pip install -e .         # 装 torch / torchvision / pillow / numpy
python -m ssd_demo --input path/to/image.jpg
```

**输出**:
```
[ssd] Loading SSD300 (voc_weights)...
[ssd] Decoding 8732 prior boxes...
[ssd] Detected 3 objects:
  - person  @ (124, 88, 312, 456)  conf=0.97
  - dog     @ (320, 200, 580, 480) conf=0.91
  - bicycle @ (50, 300, 280, 420)  conf=0.63
```

---

## 3. ⭐ 4 个核心模块

| 模块 | 文件 | 字节 | 作用 |
|---|---|---|---|
| `__main__` | `__init__.py` | 895 | CLI 入口 (FR-8 shape) |
| `decode` | `decode.py` | 12,088 | ⭐ 8732 prior box decode + NMS (核心算法) |
| `infer` | `infer.py` | 3,699 | 单图推理 (preprocess → model → decode) |
| `preprocess` | `preprocess.py` | 5,343 | 图像 resize / normalize / batch |

### 3.1 ⭐ `decode` — 算法核心

SSD 输出的 8732 个 prior box 预测需要**后处理**:
1. **Decode**: 把 (cx, cy, w, h) 偏移 + variance 解码成绝对坐标
2. **Score threshold**: 过滤 conf < threshold 的 box
3. **Per-class NMS**: 每个类独立做 Non-Maximum Suppression
4. **Top-K**: 保留 top 100 检出

`decode.py` 实现这 4 步,纯 NumPy(可选 torchvision NMS 加速)。

### 3.2 `infer` — 单图流水线

```python
def infer(image_path: str, threshold: float = 0.5) -> list[Detection]:
    img = preprocess.load_image(image_path)         # PIL → tensor [1, 3, 300, 300]
    model = load_pretrained_ssd300()                # torchvision SSD300, VOC weights
    with torch.no_grad():
        outputs = model(img)                        # [1, 8732, num_classes]
    return decode.decode(outputs, threshold)        # → List[Detection]
```

### 3.3 `preprocess` — 图像预处理

- Resize 到 300×300 (SSD300 输入尺寸)
- Normalize: mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]
- 0-1 范围 float32

---

## 4. 目录结构

```
ssd-demo/
├── pyproject.toml              # 4 个 runtime dep (NFR-3 ceiling=6, 在 budget 内)
├── ssd_demo/
│   ├── __init__.py             # CLI entry (--input, --threshold, --output-json)
│   ├── decode.py               # ⭐ 算法核心 (12 KB)
│   ├── infer.py                # 单图推理
│   └── preprocess.py           # 图像加载 + transform
├── tests/
│   ├── test_decode.py          # 19.5 KB, 覆盖 NMS 边界
│   ├── test_infer.py
│   └── test_preprocess.py
└── README.md
```

---

## 5. 跟 aethercode 集成

虽然 ssd-demo 是独立 Python 项目,但它**通过 `image_understand` tool 跟 aethercode 集成**:

```
User: "在这张图里找物体"
  ↓
[LLM 决定调 image_understand]
  ↓
[image_understand tool 调 ssd-demo subprocess]
  ↓
[ssd-demo 输出 JSON 检出]
  ↓
[tool 包装返回给 LLM]
  ↓
[LLM 总结]
```

**集成方式**:
- `image_understand` tool 内调 `python -m ssd_demo --input <path> --output-json`
- 解析 JSON 作为 tool result
- LLM 拿到结构化 detection,总结给用户

**这正是为什么 ssd-demo 存在**:它是个**真实可跑的 Python 工具**,tool 集成有抓手,不用 mock。

---

## 6. ⭐ CI 集成

`ssd-demo/tests/` 跑端到端测试,跟 R234 E2E 一起验证:
- Daemon 启动
- query 处理
- **image_understand tool 真实跑** (不 mock)
- 检出结果回传
- Daemon 关闭

CI 跑 5 分钟,跟 ssd-demo 名字里的 "5 分钟 demo" 对应。

---

## 7. 依赖管理 (NFR-3 严格)

```toml
dependencies = [
    "torch>=2.0",
    "torchvision>=0.20,<0.22",
    "pillow>=10.0",
    "numpy>=1.24",
]
```

**NFR-3 硬上限 = 6 个 runtime dep**, 当前 4 个,在 budget 内 (注释明说)。

Dev deps (pytest / ruff) 不算 runtime,**不**进 NFR-3 计数。

---

## 8. 配置 / 调参

```bash
# 最低门槛 (voc 类别阈值)
python -m ssd_demo --input img.jpg --threshold 0.5

# 严格 (高 confidence, 少检出)
python -m ssd_demo --input img.jpg --threshold 0.8 --top-k 20

# JSON 输出 (给 tool 解析)
python -m ssd_demo --input img.jpg --output-json | jq

# 批量
for img in images/*.jpg; do python -m ssd_demo --input "$img"; done
```

---

## 9. 已知 trade-off

| 决策 | 优点 | 缺点 |
|---|---|---|
| torchvision pretrained (不是自训) | 5 分钟跑通 | 不可微调 |
| Python 独立项目 | 易装, 不污染主仓 | 跨语言调用稍麻烦 |
| 8732 prior box 硬编码 (SSD300) | 简单 | 换 SSD512 要改 |
| 4 个 runtime dep (NFR-3 6 个) | 轻量 | 缺 augmentation / coco / mAP |
| CLI 是 `python -m ssd_demo` 不是 `aethercode ssd` | 标准 | 双入口 |
| 用 NMS 不 Soft-NMS | 标准 | 遮挡场景略弱 |

---

## 10. ⭐ 关键 round 引用

- **R250+**: 引入 ssd-demo 作为发布前 E2E 验证
- **R237**: 0.2.57 final release 前必跑 ssd-demo
- **R242**: 引入 image_understand tool 调 ssd-demo
- 详细过程见 `../round-notes/SSD-DEMO-RUN.md` 和 `../round-notes/SSD-WORKFLOW.md`
