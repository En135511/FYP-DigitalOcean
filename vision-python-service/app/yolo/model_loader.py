import os
from pathlib import Path
from ultralytics import YOLO


class YoloModelLoader:
    def __init__(self):
        env_model_path = os.getenv("YOLO_MODEL_PATH")
        if env_model_path:
            self.model_path = Path(env_model_path).expanduser().resolve()
            self.source = "YOLO_MODEL_PATH"
            return

        service_root = Path(__file__).resolve().parents[2]
        local_candidates = [
            (
                service_root / "model" / "yolov8_braille.pt",
                "auto:model/yolov8_braille.pt",
            ),
            (
                service_root / "model" / "dotneuralnet" / "yolov8_braille.pt",
                "auto:model/dotneuralnet/yolov8_braille.pt",
            ),
            (
                service_root / "model" / "yolo.pt",
                "auto:model/yolo.pt",
            ),
        ]

        for candidate, source in local_candidates:
            if candidate.exists():
                self.model_path = candidate.resolve()
                self.source = source
                return

        # Legacy fallback for older setups that keep DotNeuralNet as a sibling folder.
        dotneuralnet_path = service_root.parent / "DotNeuralNet-main" / "weights" / "yolov8_braille.pt"
        self.model_path = dotneuralnet_path.resolve()
        self.source = "auto:../DotNeuralNet-main/weights/yolov8_braille.pt"

    def load(self) -> YOLO:
        print("=== YOLO MODEL LOADER ===")
        print("Model source:", self.source)
        print("Resolved model path:", self.model_path)

        if not self.model_path.exists():
            raise FileNotFoundError(f"YOLO model not found: {self.model_path}")

        model = YOLO(str(self.model_path))
        print("Model loaded OK.")
        return model
