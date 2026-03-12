from typing import List, Optional
from pydantic import BaseModel


class DetectedDot(BaseModel):
    x: float
    y: float
    confidence: float


class DotDetectionResponse(BaseModel):
    imageWidth: int
    imageHeight: int
    dots: List[DetectedDot]
    brailleUnicode: Optional[str] = None
    modelType: Optional[str] = None
    uncertainCellsCount: Optional[int] = None
    reviewRecommended: Optional[bool] = None
    qualityWarning: Optional[str] = None
