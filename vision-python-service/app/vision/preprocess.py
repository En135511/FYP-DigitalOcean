from __future__ import annotations

from typing import Any, Dict, Optional, Tuple

import cv2
import numpy as np


def preprocess_for_braille(arr_rgb: np.ndarray) -> np.ndarray:
    # RGB -> Gray
    gray = cv2.cvtColor(arr_rgb, cv2.COLOR_RGB2GRAY)

    # Contrast normalization
    clahe = cv2.createCLAHE(clipLimit=2.5, tileGridSize=(8, 8))
    norm = clahe.apply(gray)

    # Unsharp mask
    blur = cv2.GaussianBlur(norm, (0, 0), sigmaX=1.2, sigmaY=1.2)
    sharp = cv2.addWeighted(norm, 1.8, blur, -0.8, 0)

    # Mild denoise
    sharp = cv2.bilateralFilter(sharp, d=5, sigmaColor=50, sigmaSpace=50)

    # Back to RGB
    return cv2.cvtColor(sharp, cv2.COLOR_GRAY2RGB)


def estimate_skew_angle(arr_rgb: np.ndarray, max_abs_angle: float = 4.0) -> float:
    gray = cv2.cvtColor(arr_rgb, cv2.COLOR_RGB2GRAY)
    blur = cv2.GaussianBlur(gray, (5, 5), 0)

    # Use horizontal-like line evidence from embossed rows.
    edges = cv2.Canny(blur, 40, 120)
    min_len = max(120, int(gray.shape[1] * 0.28))
    lines = cv2.HoughLinesP(
        edges,
        rho=1,
        theta=np.pi / 180.0,
        threshold=60,
        minLineLength=min_len,
        maxLineGap=28,
    )

    if lines is None or len(lines) == 0:
        return 0.0

    angles = []
    for line in lines:
        x1, y1, x2, y2 = line[0]
        dx = float(x2 - x1)
        dy = float(y2 - y1)
        if abs(dx) < 1.0:
            continue
        angle = float(np.degrees(np.arctan2(dy, dx)))
        if -15.0 <= angle <= 15.0:
            angles.append(angle)

    if not angles:
        return 0.0

    skew = float(np.median(np.array(angles, dtype=np.float32)))
    if abs(skew) > max_abs_angle:
        return 0.0
    return skew


def rotate_image(arr_rgb: np.ndarray, angle_degrees: float) -> Tuple[np.ndarray, np.ndarray]:
    h, w = arr_rgb.shape[:2]
    center = (w / 2.0, h / 2.0)
    matrix = cv2.getRotationMatrix2D(center, angle_degrees, 1.0).astype(np.float32)

    rotated = cv2.warpAffine(
        arr_rgb,
        matrix,
        (w, h),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_REPLICATE,
    )

    forward = np.vstack([matrix, np.array([0.0, 0.0, 1.0], dtype=np.float32)]).astype(np.float32)
    inverse = np.linalg.inv(forward).astype(np.float32)
    return rotated, inverse


def rectify_perspective(arr_rgb: np.ndarray) -> Tuple[Optional[np.ndarray], Optional[np.ndarray]]:
    h, w = arr_rgb.shape[:2]
    if h <= 0 or w <= 0:
        return None, None

    corners = _find_document_corners(arr_rgb)
    if corners is None:
        return None, None

    margin = max(8, int(min(h, w) * 0.03))
    dst = np.array(
        [
            [margin, margin],
            [w - margin - 1, margin],
            [w - margin - 1, h - margin - 1],
            [margin, h - margin - 1],
        ],
        dtype=np.float32,
    )
    src = _order_points_clockwise(corners)
    forward = cv2.getPerspectiveTransform(src, dst)
    inverse = cv2.getPerspectiveTransform(dst, src)

    warped = cv2.warpPerspective(
        arr_rgb,
        forward,
        (w, h),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_REPLICATE,
    )
    return warped, inverse.astype(np.float32)


def build_detection_variants(arr_rgb: np.ndarray) -> list[Dict[str, Any]]:
    variants: list[Dict[str, Any]] = []
    variants.append(_variant("original", arr_rgb))
    variants.append(_variant("enhanced", preprocess_for_braille(arr_rgb)))

    rectified, rectified_to_original = rectify_perspective(arr_rgb)
    if rectified is not None:
        variants.append(_variant("rectified", rectified, rectified_to_original))
        variants.append(_variant("rectified_enhanced", preprocess_for_braille(rectified), rectified_to_original))

        rectified_skew = estimate_skew_angle(rectified)
        if abs(rectified_skew) >= 0.2:
            deskewed_rectified, deskewed_rectified_to_rectified = rotate_image(rectified, -rectified_skew)
            deskewed_rectified_to_original = _compose_homographies(
                deskewed_rectified_to_rectified,
                rectified_to_original,
            )
            variants.append(
                _variant(
                    "deskewed_rectified",
                    deskewed_rectified,
                    deskewed_rectified_to_original,
                )
            )
            variants.append(
                _variant(
                    "deskewed_rectified_enhanced",
                    preprocess_for_braille(deskewed_rectified),
                    deskewed_rectified_to_original,
                )
            )

    skew_angle = estimate_skew_angle(arr_rgb)
    if abs(skew_angle) >= 0.2:
        deskewed, deskewed_to_original = rotate_image(arr_rgb, -skew_angle)
        variants.append(_variant("deskewed", deskewed, deskewed_to_original))
        variants.append(_variant("deskewed_enhanced", preprocess_for_braille(deskewed), deskewed_to_original))

    return variants


def _variant(
    name: str,
    image: np.ndarray,
    variant_to_original: Optional[np.ndarray] = None,
) -> Dict[str, Any]:
    return {
        "name": name,
        "image": image,
        "variant_to_original": None if variant_to_original is None else np.array(variant_to_original, dtype=np.float32),
    }


def _compose_homographies(
    variant_to_parent: Optional[np.ndarray],
    parent_to_original: Optional[np.ndarray],
) -> Optional[np.ndarray]:
    if variant_to_parent is None and parent_to_original is None:
        return None
    if variant_to_parent is None:
        return np.array(parent_to_original, dtype=np.float32)
    if parent_to_original is None:
        return np.array(variant_to_parent, dtype=np.float32)

    a = np.array(variant_to_parent, dtype=np.float32)
    b = np.array(parent_to_original, dtype=np.float32)
    if a.shape != (3, 3) or b.shape != (3, 3):
        return None
    return (b @ a).astype(np.float32)


def _find_document_corners(arr_rgb: np.ndarray) -> Optional[np.ndarray]:
    gray = cv2.cvtColor(arr_rgb, cv2.COLOR_RGB2GRAY)
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    th = cv2.adaptiveThreshold(
        blur,
        255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY,
        31,
        9,
    )
    inv = cv2.bitwise_not(th)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    closed = cv2.morphologyEx(inv, cv2.MORPH_CLOSE, kernel, iterations=2)

    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return None

    image_area = float(arr_rgb.shape[0] * arr_rgb.shape[1])
    min_area = image_area * 0.28

    best_quad = None
    best_area = 0.0
    for contour in contours:
        area = float(cv2.contourArea(contour))
        if area < min_area:
            continue
        perimeter = cv2.arcLength(contour, True)
        approx = cv2.approxPolyDP(contour, 0.02 * perimeter, True)
        if len(approx) != 4 or not cv2.isContourConvex(approx):
            continue
        if area > best_area:
            best_quad = approx.reshape(4, 2).astype(np.float32)
            best_area = area

    if best_quad is not None:
        return best_quad

    largest = max(contours, key=cv2.contourArea)
    area = float(cv2.contourArea(largest))
    if area < min_area:
        return None
    rect = cv2.minAreaRect(largest)
    return cv2.boxPoints(rect).astype(np.float32)


def _order_points_clockwise(pts: np.ndarray) -> np.ndarray:
    points = np.array(pts, dtype=np.float32)
    if points.shape != (4, 2):
        raise ValueError("Expected 4x2 point array")

    s = points.sum(axis=1)
    diff = np.diff(points, axis=1).reshape(-1)

    top_left = points[np.argmin(s)]
    bottom_right = points[np.argmax(s)]
    top_right = points[np.argmin(diff)]
    bottom_left = points[np.argmax(diff)]

    return np.array([top_left, top_right, bottom_right, bottom_left], dtype=np.float32)
