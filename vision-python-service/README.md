## BrailleAI Vision Service

FastAPI service that detects Braille from uploaded images.

### Endpoint

- `POST /vision/detect-dots`
  - multipart form field: `image`
  - returns:
    - `imageWidth`, `imageHeight`
    - `dots`: dot points (`x`, `y`, `confidence`) for Java pipeline compatibility
    - `brailleUnicode` (optional): Unicode Braille text when using DotNeuralNet 64-class model
    - `modelType` (optional): model mode used by inference

### Model Selection

Model path resolution order:

1. `YOLO_MODEL_PATH` environment variable (if set)
2. `../DotNeuralNet-main/weights/yolov8_braille.pt` (auto-detected)
3. `./model/yolo.pt` fallback

### Confidence / IoU

- `YOLO_CONF_THRESHOLD` (default: `0.15`)
- `YOLO_IOU_THRESHOLD` (default: `0.30`)
- `YOLO_ABSTAIN_UNCERTAIN` (default: `false`)

### Notes

- DotNeuralNet predicts 64 Braille cell classes (`000000` .. `111111`).
- The service decodes these classes into `brailleUnicode`.
- For DotNeuralNet mode, inference is fused across `original + enhanced` variants
  to reduce single-pass misclassification.
- If fused predictions disagree for a cell, the service marks that cell as uncertain
  and can abstain from emitting a hard character for that position.
- It also synthesizes dot points from each cell box so existing Java processing can remain unchanged.
- Additional response fields:
  - `uncertainCellsCount`
  - `reviewRecommended`
  - `qualityWarning`
