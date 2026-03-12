import os
import time
import logging

from fastapi import APIRouter, UploadFile, File, HTTPException
from app.schemas.dot_response import DotDetectionResponse, DetectedDot
from app.yolo.model_loader import YoloModelLoader
from app.yolo.inference_engine import YoloInferenceEngine

router = APIRouter()
logger = logging.getLogger("brailleai.vision")

# Load model once at import/startup
_model = YoloModelLoader().load()
_engine = YoloInferenceEngine(_model)


def _read_float_env(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


@router.get("/debug/model-info")
def model_info() -> dict:
    names = getattr(_model, "names", {}) or {}
    sample = []
    for key in sorted(names.keys())[:8]:
        sample.append(str(names[key]))

    return {
        "status": "ok",
        "model_path_env": os.getenv("YOLO_MODEL_PATH", ""),
        "class_count": len(names),
        "dotneuralnet_model": bool(_engine._is_dotneuralnet_model()),
        "sample_class_names": sample,
        "conf_threshold": _read_float_env("YOLO_CONF_THRESHOLD", 0.18),
        "conf_retry_threshold": _read_float_env("YOLO_CONF_RETRY_THRESHOLD", 0.08),
        "iou_threshold": _read_float_env("YOLO_IOU_THRESHOLD", 0.30),
    }


@router.post("/detect-dots", response_model=DotDetectionResponse)
async def detect_dots(image: UploadFile = File(...)) -> DotDetectionResponse:
    start = time.perf_counter()
    image_bytes = await image.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="Empty image uploaded")

    try:
        (
            dots,
            width,
            height,
            braille_unicode,
            model_type,
            uncertain_cells_count,
            review_recommended,
            quality_warning,
        ) = _engine.detect_dots(
            image_bytes,
            conf=_read_float_env("YOLO_CONF_THRESHOLD", 0.18),
            iou=_read_float_env("YOLO_IOU_THRESHOLD", 0.30)
        )
    except Exception as e:
        duration_ms = (time.perf_counter() - start) * 1000.0
        logger.exception("detect_dots_failed duration_ms=%.1f error=%s", duration_ms, str(e))
        raise HTTPException(status_code=500, detail=f"YOLO inference failed: {str(e)}")

    duration_ms = (time.perf_counter() - start) * 1000.0
    logger.info(
        "detect_dots_ok duration_ms=%.1f model=%s dots=%d uncertain=%d review=%s",
        duration_ms,
        model_type,
        len(dots),
        int(uncertain_cells_count or 0),
        bool(review_recommended),
    )

    return DotDetectionResponse(
        imageWidth=width,
        imageHeight=height,
        dots=[DetectedDot(**d) for d in dots],
        brailleUnicode=braille_unicode,
        modelType=model_type,
        uncertainCellsCount=uncertain_cells_count,
        reviewRecommended=review_recommended,
        qualityWarning=quality_warning
    )
