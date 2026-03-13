import os
from io import BytesIO
from typing import Any, Dict, List, Optional, Tuple

import cv2
import numpy as np
from PIL import Image

from app.vision.preprocess import build_detection_variants


class YoloInferenceEngine:
    _COMMON_BIGRAMS = frozenset({
        "th", "he", "in", "er", "an", "re", "on", "at", "en", "nd",
        "ti", "es", "or", "te", "of", "ed", "is", "it", "al", "ar",
        "st", "to", "nt", "ng", "se", "ha", "as", "ou", "io", "le",
        "ve", "co", "me", "de", "hi", "ri", "ro", "li", "ll", "oo",
        "qu", "ue", "ay", "oy", "ey", "ly", "pr", "pl", "cr", "gr",
        "dr", "sw", "wh", "ch", "sh", "ea", "ow", "wr", "tr",
    })
    _RARE_BIGRAMS = frozenset({
        "qx", "qz", "qj", "vq", "zx", "xz", "jj", "vv", "ww", "yy",
    })

    def __init__(self, model):
        self.model = model
        self.abstain_uncertain = self._read_bool_env("YOLO_ABSTAIN_UNCERTAIN", False)
        self.beam_width = int(max(2, min(10, self._read_float_env("YOLO_BEAM_WIDTH", 6.0))))
        self.max_variants = int(max(1, min(8, self._read_float_env("YOLO_MAX_VARIANTS", 4.0))))
        self._configure_runtime_threads()

    @staticmethod
    def _read_bool_env(name: str, default: bool) -> bool:
        value = os.getenv(name)
        if value is None:
            return default
        return value.strip().lower() in {"1", "true", "yes", "on"}

    @staticmethod
    def _read_float_env(name: str, default: float) -> float:
        value = os.getenv(name)
        if value is None:
            return default
        try:
            return float(value)
        except ValueError:
            return default

    def _configure_runtime_threads(self) -> None:
        # Keep inference resource usage predictable on small cloud instances.
        torch_threads = int(max(1, min(4, self._read_float_env("YOLO_TORCH_THREADS", 1.0))))
        cv_threads = int(max(1, min(4, self._read_float_env("YOLO_CV_THREADS", 1.0))))
        try:
            import torch  # type: ignore
            torch.set_num_threads(torch_threads)
            if hasattr(torch, "set_num_interop_threads"):
                torch.set_num_interop_threads(torch_threads)
        except Exception:
            pass

        try:
            cv2.setNumThreads(cv_threads)
        except Exception:
            pass

    def detect_dots(
        self,
        image_bytes: bytes,
        conf: float = 0.15,
        iou: float = 0.3
    ) -> Tuple[List[Dict[str, Any]], int, int, Optional[str], str, int, bool, Optional[str]]:
        """
        Returns:
            (dots, width, height, braille_unicode, model_type,
             uncertain_cells_count, review_recommended, quality_warning)
        dots = [{x, y, confidence}, ...]

        `braille_unicode` is populated when using a 64-class DotNeuralNet model
        (class names like "100000").
        """

        img = Image.open(BytesIO(image_bytes)).convert("RGB")
        width, height = img.size
        arr = np.array(img)

        if self._is_dotneuralnet_model():
            return self._detect_dotneuralnet(arr, width, height, conf, iou)

        results = self.model.predict(source=arr, conf=conf, iou=iou, verbose=False)
        if not results:
            return [], width, height, None, "unknown", 0, False, None

        r0 = results[0]
        if r0.boxes is None or len(r0.boxes) == 0:
            model_type = "dotneuralnet-cell-64" if self._is_dotneuralnet_model() else "dot-center-detector"
            return [], width, height, "", model_type, 0, False, None

        # Fallback path for legacy single-class dot detectors.
        dots: List[Dict[str, Any]] = []
        for box in r0.boxes:
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            dots.append({
                "x": (x1 + x2) / 2.0,
                "y": (y1 + y2) / 2.0,
                "confidence": float(box.conf[0].item())
            })

        return dots, width, height, None, "dot-center-detector", 0, False, None

    def _detect_dotneuralnet(
        self,
        arr: np.ndarray,
        width: int,
        height: int,
        conf: float,
        iou: float
    ) -> Tuple[List[Dict[str, Any]], int, int, Optional[str], str, int, bool, Optional[str]]:
        variants = build_detection_variants(arr)
        if len(variants) > self.max_variants:
            variants = variants[:self.max_variants]

        all_cells = self._collect_cells_from_variants(variants, width, height, conf, iou)

        retry_conf = self._read_float_env("YOLO_CONF_RETRY_THRESHOLD", 0.08)
        if not all_cells and 0.0 < retry_conf < conf:
            all_cells = self._collect_cells_from_variants(variants, width, height, retry_conf, iou)

        if not all_cells:
            return (
                [],
                width,
                height,
                "",
                "dotneuralnet-cell-64-consensus",
                0,
                False,
                f"No Braille cells detected (conf={conf:.2f}, retry={retry_conf:.2f})."
            )

        fused_cells, uncertain_cells_count = self._fuse_cells(all_cells)
        braille_unicode = self._cells_to_braille_unicode(fused_cells)
        synthetic_dots = self._cells_to_dot_points(fused_cells, width, height)

        total_cells = len(fused_cells)
        threshold = max(2, int(round(total_cells * 0.03)))
        review_recommended = uncertain_cells_count >= threshold

        quality_warning: Optional[str] = None
        if uncertain_cells_count > 0:
            quality_warning = (
                f"Model marked {uncertain_cells_count} low-consensus cells."
            )
        if review_recommended:
            quality_warning = (
                f"{quality_warning} "
                "Manual review is recommended."
            )

        return (
            synthetic_dots,
            width,
            height,
            braille_unicode,
            "dotneuralnet-cell-64-consensus",
            uncertain_cells_count,
            review_recommended,
            quality_warning,
        )

    def _collect_cells_from_variants(
        self,
        variants: List[Any],
        width: int,
        height: int,
        conf: float,
        iou: float
    ) -> List[Dict[str, Any]]:
        all_cells: List[Dict[str, Any]] = []

        for variant_meta in variants:
            # Backward-compatible with both:
            # - new dict metadata variants
            # - legacy tuple variants: (name, image)
            if isinstance(variant_meta, dict):
                source = str(variant_meta.get("name", "original"))
                variant = variant_meta.get("image")
                variant_to_original = variant_meta.get("variant_to_original")
            elif isinstance(variant_meta, tuple) and len(variant_meta) >= 2:
                source = str(variant_meta[0])
                variant = variant_meta[1]
                variant_to_original = None
            else:
                continue
            if variant is None:
                continue

            results = self.model.predict(source=variant, conf=conf, iou=iou, verbose=False)
            if not results:
                continue

            first = results[0]
            if first.boxes is None or len(first.boxes) == 0:
                continue

            cells = self._extract_cells(
                first.boxes,
                variant_to_original=variant_to_original,
                image_width=width,
                image_height=height,
            )
            for cell in cells:
                cell["source"] = source
            all_cells.extend(cells)

        return all_cells

    def _is_dotneuralnet_model(self) -> bool:
        names = getattr(self.model, "names", {}) or {}
        if len(names) < 64:
            return False
        return all(self._is_6dot_bitstring(str(name)) for name in names.values())

    @staticmethod
    def _is_6dot_bitstring(value: str) -> bool:
        return len(value) == 6 and all(ch in ("0", "1") for ch in value)

    def _extract_cells(
        self,
        boxes,
        variant_to_original: Optional[np.ndarray] = None,
        image_width: Optional[int] = None,
        image_height: Optional[int] = None,
    ) -> List[Dict[str, Any]]:
        names = getattr(self.model, "names", {}) or {}
        cells: List[Dict[str, Any]] = []

        for box in boxes:
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            mapped_x1, mapped_y1, mapped_x2, mapped_y2 = self._map_box_to_original(
                x1, y1, x2, y2, variant_to_original, image_width, image_height
            )

            cls_id = int(box.cls[0].item())
            conf = float(box.conf[0].item())
            bit_pattern = str(names.get(cls_id, ""))
            unicode_char = self._bitstring_to_unicode(bit_pattern)

            cells.append({
                "x": (mapped_x1 + mapped_x2) / 2.0,
                "y": (mapped_y1 + mapped_y2) / 2.0,
                "w": max(1.0, mapped_x2 - mapped_x1),
                "h": max(1.0, mapped_y2 - mapped_y1),
                "confidence": conf,
                "classId": cls_id,
                "pattern": bit_pattern,
                "unicode": unicode_char,
            })

        return cells

    def _map_box_to_original(
        self,
        x1: float,
        y1: float,
        x2: float,
        y2: float,
        variant_to_original: Optional[np.ndarray],
        image_width: Optional[int],
        image_height: Optional[int],
    ) -> Tuple[float, float, float, float]:
        if variant_to_original is None:
            return x1, y1, x2, y2

        h = np.array(variant_to_original, dtype=np.float32)
        if h.shape != (3, 3):
            return x1, y1, x2, y2

        pts = np.array(
            [[[x1, y1], [x2, y1], [x2, y2], [x1, y2]]],
            dtype=np.float32,
        )
        mapped = cv2.perspectiveTransform(pts, h)[0]
        xs = mapped[:, 0]
        ys = mapped[:, 1]

        min_x = float(np.min(xs))
        max_x = float(np.max(xs))
        min_y = float(np.min(ys))
        max_y = float(np.max(ys))

        if image_width is not None and image_width > 0:
            min_x = self._clamp(min_x, 0.0, float(image_width - 1))
            max_x = self._clamp(max_x, 0.0, float(image_width - 1))
        if image_height is not None and image_height > 0:
            min_y = self._clamp(min_y, 0.0, float(image_height - 1))
            max_y = self._clamp(max_y, 0.0, float(image_height - 1))

        if max_x <= min_x:
            max_x = min_x + 1.0
        if max_y <= min_y:
            max_y = min_y + 1.0

        return min_x, min_y, max_x, max_y

    def _fuse_cells(self, cells: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], int]:
        if not cells:
            return [], 0

        valid_w = [float(c["w"]) for c in cells if float(c["w"]) > 0.0]
        valid_h = [float(c["h"]) for c in cells if float(c["h"]) > 0.0]
        median_w = float(np.median(valid_w)) if valid_w else 12.0
        median_h = float(np.median(valid_h)) if valid_h else 14.0
        match_dx = max(8.0, median_w * 0.6)
        match_dy = max(8.0, median_h * 0.75)

        source_weight = {
            "original": 1.0,
            "enhanced": 1.15,
            "rectified": 1.18,
            "rectified_enhanced": 1.24,
            "deskewed": 1.1,
            "deskewed_enhanced": 1.2,
        }

        clusters: List[Dict[str, Any]] = []
        sorted_cells = sorted(cells, key=lambda d: (float(d["y"]), float(d["x"])))

        for cell in sorted_cells:
            x = float(cell["x"])
            y = float(cell["y"])
            match_index = -1

            for idx in range(len(clusters) - 1, -1, -1):
                center_x, center_y = clusters[idx]["center"]
                if y - center_y > (match_dy * 1.6):
                    break
                if abs(x - center_x) <= match_dx and abs(y - center_y) <= match_dy:
                    match_index = idx
                    break

            if match_index < 0:
                clusters.append({
                    "items": [cell],
                    "center": [x, y],
                })
                continue

            cluster = clusters[match_index]
            cluster["items"].append(cell)
            n = len(cluster["items"])
            cluster["center"][0] = (cluster["center"][0] * (n - 1) + x) / n
            cluster["center"][1] = (cluster["center"][1] * (n - 1) + y) / n

        fused: List[Dict[str, Any]] = []
        uncertain_count = 0
        for cluster in clusters:
            pattern_scores: Dict[str, float] = {}
            for item in cluster["items"]:
                pattern = str(item["pattern"])
                score = float(item["confidence"]) * source_weight.get(str(item.get("source")), 1.0)
                pattern_scores[pattern] = pattern_scores.get(pattern, 0.0) + score

            best_pattern = max(pattern_scores.items(), key=lambda kv: kv[1])[0]
            chosen = [i for i in cluster["items"] if str(i["pattern"]) == best_pattern]

            sorted_scores = sorted(pattern_scores.values(), reverse=True)
            best_score = float(sorted_scores[0]) if sorted_scores else 0.0
            second_score = float(sorted_scores[1]) if len(sorted_scores) > 1 else 0.0
            total_score = float(sum(sorted_scores)) if sorted_scores else 0.0

            dominance = (best_score / total_score) if total_score > 0.0 else 1.0
            ratio = (best_score / second_score) if second_score > 1e-9 else 999.0
            top_conf = max(float(i["confidence"]) for i in chosen)
            support_variants = len({str(i.get("source", "")) for i in chosen})
            uncertain = False

            if len(pattern_scores) > 1 and (dominance < 0.66 or ratio < 1.50):
                uncertain = True
            if len(chosen) == 1 and top_conf < 0.34:
                uncertain = True
            if support_variants < 2 and top_conf < 0.56:
                uncertain = True

            if uncertain:
                uncertain_count += 1

            total = total_score if total_score > 1e-9 else 1.0
            ranked_patterns = sorted(
                pattern_scores.items(),
                key=lambda kv: kv[1],
                reverse=True,
            )
            candidates = []
            for idx, (pattern, score) in enumerate(ranked_patterns[:3]):
                candidates.append({
                    "pattern": pattern,
                    "unicode": self._bitstring_to_unicode(pattern),
                    "score": float(score),
                    "prob": float(score / total),
                    "rank": idx + 1,
                })

            fused.append({
                "x": sum(float(i["x"]) for i in chosen) / len(chosen),
                "y": sum(float(i["y"]) for i in chosen) / len(chosen),
                "w": sum(float(i["w"]) for i in chosen) / len(chosen),
                "h": sum(float(i["h"]) for i in chosen) / len(chosen),
                "confidence": max(float(i["confidence"]) for i in chosen),
                "classId": int(chosen[0]["classId"]),
                "pattern": best_pattern,
                "unicode": self._bitstring_to_unicode(best_pattern),
                "uncertain": uncertain,
                "dominance": dominance,
                "supportVariants": support_variants,
                "candidates": candidates,
            })

        return fused, uncertain_count

    def _cells_to_braille_unicode(self, cells: List[Dict[str, Any]]) -> str:
        if not cells:
            return ""

        # Cluster by y-position, then sort each row by x-position.
        sorted_cells = sorted(cells, key=lambda c: c["y"])
        avg_h = sum(c["h"] for c in sorted_cells) / len(sorted_cells)
        y_threshold = max(8.0, avg_h * 0.55)

        rows: List[List[Dict[str, Any]]] = []
        current_row: List[Dict[str, Any]] = []
        current_y_mean = 0.0

        for cell in sorted_cells:
            if not current_row:
                current_row = [cell]
                current_y_mean = cell["y"]
                continue

            if abs(cell["y"] - current_y_mean) <= y_threshold:
                current_row.append(cell)
                current_y_mean = sum(c["y"] for c in current_row) / len(current_row)
            else:
                rows.append(current_row)
                current_row = [cell]
                current_y_mean = cell["y"]

        if current_row:
            rows.append(current_row)

        lines: List[str] = []
        for row in rows:
            ordered = sorted(row, key=lambda c: c["x"])
            ordered = self._deduplicate_pairwise_row_cells(ordered)
            avg_conf = sum(float(c.get("confidence", 0.0)) for c in ordered) / len(ordered)
            if len(ordered) <= 2 and avg_conf < 0.55:
                continue
            if self.abstain_uncertain and all(bool(c.get("uncertain")) for c in ordered):
                continue
            line = self._decode_row_with_beam(ordered)
            if line:
                lines.append(line)

        return "\n".join(lines)

    def _deduplicate_pairwise_row_cells(self, ordered_row: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        Collapse split-cell duplication artifacts like:
        jjeemmiimmaah -> jemimah

        Guard rails:
        - only triggers when a row has a strong repeated-pair pattern
        - only merges adjacent cells with identical patterns and unusually tight gaps
        """
        n = len(ordered_row)
        if n < 6:
            return ordered_row

        gaps: List[float] = []
        for i in range(n - 1):
            gap = float(ordered_row[i + 1]["x"]) - float(ordered_row[i]["x"])
            if gap > 0:
                gaps.append(gap)
        if not gaps:
            return ordered_row

        median_gap = float(np.median(gaps))
        tight_gap_threshold = max(8.0, median_gap * 0.92)

        def is_duplicate_pair(i: int) -> bool:
            if i < 0 or i + 1 >= n:
                return False

            left = ordered_row[i]
            right = ordered_row[i + 1]
            if str(left.get("pattern", "")) != str(right.get("pattern", "")):
                return False

            gap = float(right["x"]) - float(left["x"])
            if gap <= 0.0 or gap > tight_gap_threshold:
                return False

            # In split-cell artifacts, the following gap is usually larger than
            # the intra-pair gap.
            if i + 2 < n:
                next_gap = float(ordered_row[i + 2]["x"]) - float(right["x"])
                if next_gap > 0.0 and next_gap < (gap * 1.08):
                    return False

            return True

        best_pairs: List[int] = []
        best_coverage = 0.0
        best_longest_run = 0

        for offset in (0, 1):
            pairs: List[int] = []
            i = offset
            while i + 1 < n:
                if is_duplicate_pair(i):
                    pairs.append(i)
                    i += 2
                    continue
                i += 1

            if not pairs:
                continue

            coverage = (2.0 * len(pairs)) / float(max(1, n - offset))
            longest_run = 1
            run = 1
            for j in range(1, len(pairs)):
                if pairs[j] == pairs[j - 1] + 2:
                    run += 1
                else:
                    longest_run = max(longest_run, run)
                    run = 1
            longest_run = max(longest_run, run)

            if coverage > best_coverage or (coverage == best_coverage and longest_run > best_longest_run):
                best_pairs = pairs
                best_coverage = coverage
                best_longest_run = longest_run

        # Require a clear pairwise duplication signal.
        if len(best_pairs) < 3:
            return ordered_row
        if best_coverage < 0.60:
            return ordered_row
        if best_longest_run < 3:
            return ordered_row

        pair_starts = set(best_pairs)
        merged_row: List[Dict[str, Any]] = []
        i = 0
        while i < n:
            if i in pair_starts and i + 1 < n:
                merged_row.append(self._merge_duplicate_pair_cells(ordered_row[i], ordered_row[i + 1]))
                i += 2
                continue
            merged_row.append(ordered_row[i])
            i += 1

        return merged_row

    @staticmethod
    def _merge_duplicate_pair_cells(left: Dict[str, Any], right: Dict[str, Any]) -> Dict[str, Any]:
        left_conf = float(left.get("confidence", 0.0))
        right_conf = float(right.get("confidence", 0.0))
        base = dict(left if left_conf >= right_conf else right)

        base["x"] = (float(left["x"]) + float(right["x"])) / 2.0
        base["y"] = (float(left["y"]) + float(right["y"])) / 2.0
        base["w"] = max(float(left.get("w", 0.0)), float(right.get("w", 0.0)))
        base["h"] = max(float(left.get("h", 0.0)), float(right.get("h", 0.0)))
        base["confidence"] = max(left_conf, right_conf)
        base["uncertain"] = bool(left.get("uncertain")) or bool(right.get("uncertain"))
        base["dominance"] = max(float(left.get("dominance", 0.0)), float(right.get("dominance", 0.0)))
        base["supportVariants"] = max(int(left.get("supportVariants", 1)), int(right.get("supportVariants", 1)))
        return base

    def _decode_row_with_beam(self, ordered_row: List[Dict[str, Any]]) -> str:
        valid = [c for c in ordered_row if c.get("unicode") is not None]
        if not valid:
            return ""

        slots: List[Dict[str, Any]] = []
        deltas: List[float] = []
        for i in range(len(valid) - 1):
            gap = float(valid[i + 1]["x"]) - float(valid[i]["x"])
            if gap > 0:
                deltas.append(gap)

        base_step = float(np.median(deltas)) if deltas else 0.0

        for idx, cell in enumerate(valid):
            slots.append({"type": "cell", "cell": cell})
            if idx >= len(valid) - 1 or base_step <= 0.0:
                continue
            gap = float(valid[idx + 1]["x"]) - float(cell["x"])
            if gap <= (base_step * 1.65):
                continue
            blank_cells = int(round(gap / base_step)) - 1
            blank_cells = max(1, min(blank_cells, 3))
            slots.append({"type": "blank", "count": blank_cells})

        numbered_item = self._looks_like_numbered_item(valid)
        sentence_like_line = self._looks_like_sentence_like_line(valid)
        last_cell_slot_index = -1
        for idx in range(len(slots) - 1, -1, -1):
            if slots[idx]["type"] == "cell":
                last_cell_slot_index = idx
                break

        beams: List[Dict[str, Any]] = [{
            "text": "",
            "score": 0.0,
            "prev_kind": "start",
            "prev_char": None,
        }]
        blank_char = chr(0x2800)
        width = self.beam_width

        for slot_idx, slot in enumerate(slots):
            new_beams: List[Dict[str, Any]] = []
            if slot["type"] == "blank":
                space_chunk = blank_char * int(slot["count"])
                for beam in beams:
                    new_beams.append({
                        "text": beam["text"] + space_chunk,
                        "score": beam["score"] + (0.03 * int(slot["count"])),
                        "prev_kind": "space",
                        "prev_char": blank_char,
                    })
            else:
                cell = slot["cell"]
                is_line_end = slot_idx == last_cell_slot_index
                followed_by_blank = (
                    slot_idx + 1 < len(slots) and slots[slot_idx + 1]["type"] == "blank"
                )
                candidates = self._cell_candidates_for_beam(
                    cell,
                    is_line_end=is_line_end,
                    punctuation_break=followed_by_blank,
                    numbered_item=numbered_item,
                    sentence_like_line=sentence_like_line,
                )
                for beam in beams:
                    for cand in candidates:
                        char = cand["unicode"]
                        if char is None:
                            continue
                        char_kind = self._braille_char_kind(char)
                        transition = self._beam_transition_score(
                            beam["prev_kind"],
                            char_kind,
                            beam.get("prev_char"),
                            char,
                            is_line_end=is_line_end,
                            punctuation_break=followed_by_blank,
                            numbered_item=numbered_item,
                            sentence_like_line=sentence_like_line,
                        )
                        score = beam["score"] + float(np.log(max(cand["prob"], 1e-6))) + transition
                        new_beams.append({
                            "text": beam["text"] + char,
                            "score": score,
                            "prev_kind": char_kind,
                            "prev_char": char,
                        })

            if not new_beams:
                continue
            new_beams.sort(key=lambda b: b["score"], reverse=True)
            beams = new_beams[:width]

        if not beams:
            return ""
        best_text = beams[0]["text"]
        best_text = self._force_and_contraction_to_y(best_text)
        best_text = self._normalize_numeric_separator_confusions(best_text)
        best_text = self._normalize_common_mirror_letter_confusions(best_text)
        return self._normalize_sentence_terminal_punctuation(
            best_text,
            numbered_item=numbered_item,
            sentence_like_line=sentence_like_line,
        )

    @staticmethod
    def _force_and_contraction_to_y(text: str) -> str:
        if not text:
            return text
        and_cell = chr(0x282F)  # "and" contraction sign
        y_cell = chr(0x283D)    # letter "y"
        return text.replace(and_cell, y_cell)

    def _normalize_numeric_separator_confusions(self, row_text: str) -> str:
        """
        Correct common detector confusions inside numeric runs:
        - apostrophe sign (dot-3, U+2804)
        - capital sign (dot-6, U+2820)
        when they appear between Braille digits, they should be comma (U+2802).
        """
        if not row_text:
            return row_text

        number_sign = chr(0x283C)
        blank_cell = chr(0x2800)
        comma_cell = chr(0x2802)
        ambiguous_separators = {chr(0x2804), chr(0x2820)}

        chars = list(row_text)
        in_number = False
        seen_digit = False

        for idx, ch in enumerate(chars):
            if ch == number_sign:
                in_number = True
                seen_digit = False
                continue

            if ch == blank_cell:
                in_number = False
                seen_digit = False
                continue

            if not in_number:
                continue

            if self._is_braille_digit_cell(ch):
                seen_digit = True
                continue

            if ch == comma_cell:
                continue

            if ch in ambiguous_separators and seen_digit:
                next_ch = chars[idx + 1] if idx + 1 < len(chars) else None
                if next_ch is not None and self._is_braille_digit_cell(next_ch):
                    chars[idx] = comma_cell
                    continue

            in_number = False
            seen_digit = False

        return "".join(chars)

    def _normalize_common_mirror_letter_confusions(self, row_text: str) -> str:
        """
        Fix recurrent mirror-like letter confusions in alphabetic words.
        Current targeted correction:
        - word-start "ix..." -> "ex..."
        - "...xir..." -> "...xer..."
        """
        if not row_text:
            return row_text

        blank_cell = chr(0x2800)
        i_cell = chr(0x280A)
        e_cell = chr(0x2811)
        x_cell = chr(0x282D)
        r_cell = chr(0x2817)

        chars = list(row_text)
        n = len(chars)
        idx = 0
        while idx < n:
            while idx < n and chars[idx] == blank_cell:
                idx += 1
            if idx >= n:
                break

            end = idx
            while end < n and chars[end] != blank_cell:
                end += 1

            word = chars[idx:end]
            if word and all(self._braille_char_kind(ch) == "letter" for ch in word):
                if len(word) >= 2 and word[0] == i_cell and word[1] == x_cell:
                    word[0] = e_cell

                for j in range(0, len(word) - 2):
                    if word[j] == x_cell and word[j + 1] == i_cell and word[j + 2] == r_cell:
                        word[j + 1] = e_cell

                chars[idx:end] = word

            idx = end + 1

        return "".join(chars)

    def _cell_candidates_for_beam(
        self,
        cell: Dict[str, Any],
        *,
        is_line_end: bool = False,
        punctuation_break: bool = False,
        numbered_item: bool = False,
        sentence_like_line: bool = False
    ) -> List[Dict[str, Any]]:
        candidates = list(cell.get("candidates") or [])
        if not candidates:
            return [{
                "pattern": str(cell.get("pattern", "")),
                "unicode": cell.get("unicode"),
                "prob": 1.0,
            }]

        uncertain = bool(cell.get("uncertain"))
        dominance = float(cell.get("dominance", 1.0))
        support_variants = int(cell.get("supportVariants", 1))

        if uncertain:
            selected = candidates[: min(3, len(candidates))]
        elif dominance < 0.82 and support_variants < 2 and len(candidates) > 1:
            selected = candidates[:2]
        else:
            selected = candidates[:1]

        selected = self._promote_period_at_line_end(
            selected=selected,
            all_candidates=candidates,
            is_line_end=is_line_end,
            punctuation_break=punctuation_break,
            numbered_item=numbered_item,
            sentence_like_line=sentence_like_line,
        )

        result = []
        for cand in selected:
            prob = float(cand.get("prob", 0.0))
            result.append({
                "pattern": str(cand.get("pattern", "")),
                "unicode": cand.get("unicode"),
                "prob": prob,
            })

        self._maybe_append_mirror_candidate(
            result=result,
            cell=cell,
            uncertain=uncertain,
            dominance=dominance,
            support_variants=support_variants,
        )

        if uncertain and self.abstain_uncertain:
            best_prob = float(result[0]["prob"]) if result else 0.4
            result.append({
                "pattern": "000000",
                "unicode": chr(0x2800),
                "prob": min(0.55, max(0.15, best_prob * 0.75)),
            })

        total = sum(max(float(c["prob"]), 1e-9) for c in result)
        if total <= 0.0:
            return [{"pattern": str(cell.get("pattern", "")), "unicode": cell.get("unicode"), "prob": 1.0}]
        for cand in result:
            cand["prob"] = float(max(float(cand["prob"]), 1e-9) / total)
        return result

    def _maybe_append_mirror_candidate(
        self,
        *,
        result: List[Dict[str, Any]],
        cell: Dict[str, Any],
        uncertain: bool,
        dominance: float,
        support_variants: int
    ) -> None:
        if not result:
            return
        if not uncertain and dominance >= 0.90 and support_variants >= 3:
            return

        base_pattern = str(cell.get("pattern", ""))
        mirror_pattern = self._mirror_6dot_bitstring(base_pattern)
        if mirror_pattern is None or mirror_pattern == base_pattern:
            return
        if any(str(c.get("pattern", "")) == mirror_pattern for c in result):
            return

        mirror_unicode = self._bitstring_to_unicode(mirror_pattern)
        if mirror_unicode is None:
            return

        base_unicode = result[0].get("unicode")
        if self._braille_char_kind(mirror_unicode) != "letter":
            return
        if base_unicode is not None and self._braille_char_kind(base_unicode) != "letter":
            return

        top_prob = max(float(result[0].get("prob", 0.0)), 1e-6)
        prob_scale = 0.46 if uncertain else 0.30
        if dominance < 0.82 or support_variants < 2:
            prob_scale += 0.10

        mirror_prob = min(0.34, max(0.04, top_prob * prob_scale))
        result.append({
            "pattern": mirror_pattern,
            "unicode": mirror_unicode,
            "prob": mirror_prob,
        })

    def _mirror_6dot_bitstring(self, value: str) -> Optional[str]:
        if not self._is_6dot_bitstring(value):
            return None
        bits = list(value)
        # 1<->4, 2<->5, 3<->6
        return (
            bits[3]
            + bits[4]
            + bits[5]
            + bits[0]
            + bits[1]
            + bits[2]
        )

    def _beam_transition_score(
        self,
        prev_kind: str,
        current_kind: str,
        prev_char: Optional[str],
        current_char: Optional[str],
        *,
        is_line_end: bool = False,
        punctuation_break: bool = False,
        numbered_item: bool = False,
        sentence_like_line: bool = False
    ) -> float:
        score = 0.0

        if prev_kind == "start":
            if current_kind == "punct":
                score -= 0.15
            return score

        if prev_kind == "space":
            if current_kind == "punct":
                score -= 0.18
            if current_kind == "letter":
                score += 0.06

        elif prev_kind == "letter":
            if current_kind == "letter":
                score += 0.05
                score += self._letter_bigram_score(prev_char, current_char)
            if current_kind == "punct":
                score += 0.04

        elif prev_kind == "punct":
            if current_kind == "punct":
                score -= 0.40
            if current_kind == "letter":
                score -= 0.02

        # Near line endings for numbered items (for example "#1." patterns),
        # prefer a period over common one-dot confusion classes.
        period_cell = chr(0x2832)      # full stop
        comma_cell = chr(0x2802)       # comma
        apostrophe_cell = chr(0x2804)  # apostrophe
        open_quote_like_cell = chr(0x2836)

        if numbered_item and is_line_end and current_kind == "punct":
            if current_char == period_cell:
                score += 0.45
            elif current_char in {comma_cell, apostrophe_cell, open_quote_like_cell}:
                score -= 0.35

        # Apply a softer preference at sentence-like line endings.
        sentence_boundary_like = is_line_end or punctuation_break
        if sentence_like_line and sentence_boundary_like and current_kind == "punct":
            if current_char == period_cell:
                score += 0.28
            elif current_char in {comma_cell, apostrophe_cell, open_quote_like_cell}:
                score -= 0.18

        if self._is_braille_digit_cell(prev_char) and current_kind == "punct":
            if current_char == period_cell:
                score += 0.10
            elif current_char in {comma_cell, apostrophe_cell}:
                score -= 0.10

        return score

    def _letter_bigram_score(self, prev_char: Optional[str], current_char: Optional[str]) -> float:
        prev_letter = self._braille_to_ascii_letter(prev_char)
        current_letter = self._braille_to_ascii_letter(current_char)
        if prev_letter is None or current_letter is None:
            return 0.0

        pair = prev_letter + current_letter

        score = 0.0

        # Targeted disambiguation for common OCR confusion at the start of
        # "exercise" (ex) versus unlikely "ix".
        if pair == "ex":
            score += 0.24
        if pair == "ix":
            score -= 0.14

        if pair in self._COMMON_BIGRAMS:
            score += 0.035
        if pair in self._RARE_BIGRAMS:
            score -= 0.05

        # English Braille words are very likely to keep "q" followed by "u".
        if prev_letter == "q":
            if current_letter == "u":
                score += 0.18
            else:
                score -= 0.22

        # Discourage highly unlikely dense-consonant mirror artifacts.
        if prev_letter in {"x", "z", "j"} and current_letter in {"x", "z", "j"}:
            score -= 0.06

        return score

    @staticmethod
    def _braille_to_ascii_letter(ch: Optional[str]) -> Optional[str]:
        if ch is None:
            return None
        mapping = {
            chr(0x2801): "a",
            chr(0x2803): "b",
            chr(0x2809): "c",
            chr(0x2819): "d",
            chr(0x2811): "e",
            chr(0x280B): "f",
            chr(0x281B): "g",
            chr(0x2813): "h",
            chr(0x280A): "i",
            chr(0x281A): "j",
            chr(0x2805): "k",
            chr(0x2807): "l",
            chr(0x280D): "m",
            chr(0x281D): "n",
            chr(0x2815): "o",
            chr(0x280F): "p",
            chr(0x281F): "q",
            chr(0x2817): "r",
            chr(0x280E): "s",
            chr(0x281E): "t",
            chr(0x2825): "u",
            chr(0x2827): "v",
            chr(0x283A): "w",
            chr(0x282D): "x",
            chr(0x283D): "y",
            chr(0x2835): "z",
        }
        return mapping.get(ch)

    def _looks_like_numbered_item(self, row_cells: List[Dict[str, Any]]) -> bool:
        if len(row_cells) < 3:
            return False

        number_sign = chr(0x283C)
        first = row_cells[0].get("unicode")
        if first != number_sign:
            return False

        digit_count = 0
        for cell in row_cells[1:]:
            ch = cell.get("unicode")
            if self._is_braille_digit_cell(ch):
                digit_count += 1
                continue
            break

        return digit_count > 0

    def _looks_like_sentence_like_line(self, row_cells: List[Dict[str, Any]]) -> bool:
        if len(row_cells) < 4:
            return False
        if self._looks_like_numbered_item(row_cells):
            return False

        lexical_cells = 0
        for cell in row_cells:
            ch = cell.get("unicode")
            kind = self._braille_char_kind(ch) if ch is not None else "space"
            if kind in {"letter", "number"}:
                lexical_cells += 1

        return lexical_cells >= 4

    def _promote_period_at_line_end(
        self,
        selected: List[Dict[str, Any]],
        all_candidates: List[Dict[str, Any]],
        *,
        is_line_end: bool,
        punctuation_break: bool,
        numbered_item: bool,
        sentence_like_line: bool
    ) -> List[Dict[str, Any]]:
        sentence_boundary_like = is_line_end or punctuation_break
        should_apply = (numbered_item and is_line_end) or (sentence_like_line and sentence_boundary_like)
        if not should_apply:
            return selected
        if len(all_candidates) < 2:
            return selected

        period_cell = chr(0x2832)      # full stop
        comma_cell = chr(0x2802)       # comma
        apostrophe_cell = chr(0x2804)  # apostrophe
        open_quote_like_cell = chr(0x2836)

        top = all_candidates[0]
        second = all_candidates[1]
        top_unicode = top.get("unicode")
        second_unicode = second.get("unicode")
        second_prob = float(second.get("prob", 0.0))

        confusion = (
            top_unicode in {comma_cell, apostrophe_cell, open_quote_like_cell}
            and second_unicode == period_cell
        )
        min_alt_prob = 0.08 if numbered_item else 0.16
        if not confusion or second_prob < min_alt_prob:
            return selected

        boosted = all_candidates[: max(2, len(selected))]
        adjusted: List[Dict[str, Any]] = []
        for cand in boosted:
            base_prob = max(float(cand.get("prob", 0.0)), 1e-9)
            uni = cand.get("unicode")
            if uni == period_cell:
                weight = 2.50 if numbered_item else 1.90
            elif uni in {comma_cell, apostrophe_cell, open_quote_like_cell}:
                weight = 0.65 if numbered_item else 0.82
            else:
                weight = 1.0

            adjusted.append({
                "pattern": str(cand.get("pattern", "")),
                "unicode": uni,
                "prob": base_prob * weight,
            })

        return adjusted

    def _normalize_sentence_terminal_punctuation(
        self,
        row_text: str,
        *,
        numbered_item: bool,
        sentence_like_line: bool
    ) -> str:
        if not row_text:
            return row_text

        period_cell = chr(0x2832)      # full stop
        comma_cell = chr(0x2802)       # comma
        apostrophe_cell = chr(0x2804)  # apostrophe
        blank_cell = chr(0x2800)

        chars = list(row_text)
        for idx, ch in enumerate(chars):
            if ch not in {comma_cell, apostrophe_cell}:
                continue

            prev = chars[idx - 1] if idx > 0 else None
            prev_kind = self._braille_char_kind(prev) if prev is not None else "space"
            if prev_kind not in {"letter", "number"}:
                continue

            next_ch = chars[idx + 1] if idx + 1 < len(chars) else None
            next2 = chars[idx + 2] if idx + 2 < len(chars) else None
            followed_by_word_boundary = (
                next_ch == blank_cell
                and next2 is not None
                and self._braille_char_kind(next2) in {"letter", "number"}
            )
            is_terminal = idx == (len(chars) - 1)

            if numbered_item and (is_terminal or followed_by_word_boundary):
                chars[idx] = period_cell
                continue

            if sentence_like_line and (is_terminal or followed_by_word_boundary):
                chars[idx] = period_cell

        return "".join(chars)

    @staticmethod
    def _is_braille_digit_cell(ch: Optional[str]) -> bool:
        if ch is None:
            return False
        return ch in {
            chr(0x2801),  # a -> 1
            chr(0x2803),  # b -> 2
            chr(0x2809),  # c -> 3
            chr(0x2819),  # d -> 4
            chr(0x2811),  # e -> 5
            chr(0x280B),  # f -> 6
            chr(0x281B),  # g -> 7
            chr(0x2813),  # h -> 8
            chr(0x280A),  # i -> 9
            chr(0x281A),  # j -> 0
        }

    def _braille_char_kind(self, ch: str) -> str:
        if ch == chr(0x2800):
            return "space"

        punctuation_cells = {
            chr(0x2802),  # comma
            chr(0x2806),  # semicolon
            chr(0x2812),  # colon
            chr(0x2832),  # period
            chr(0x2816),  # exclamation
            chr(0x2826),  # question
            chr(0x2804),  # apostrophe
            chr(0x2824),  # hyphen
            chr(0x2836),  # opening quote (common)
            chr(0x2826),  # question/open quote overlap
        }
        if ch in punctuation_cells:
            return "punct"

        number_sign = chr(0x283C)
        if ch == number_sign:
            return "number"

        return "letter"

    def _cells_to_dot_points(
        self,
        cells: List[Dict[str, Any]],
        image_width: int,
        image_height: int
    ) -> List[Dict[str, Any]]:
        """
        Expand 6-bit cell predictions into synthetic dot points so the existing
        Java vision pipeline can remain unchanged.
        """
        dots: List[Dict[str, Any]] = []

        for cell in cells:
            bit_pattern = cell["pattern"]
            if not self._is_6dot_bitstring(bit_pattern):
                continue

            x_center = float(cell["x"])
            y_center = float(cell["y"])
            w = float(cell["w"])
            h = float(cell["h"])
            confidence = float(cell["confidence"])

            left_x = x_center - (w * 0.25)
            right_x = x_center + (w * 0.25)
            top_y = y_center - (h / 3.0)
            mid_y = y_center
            bottom_y = y_center + (h / 3.0)

            positions = [
                (left_x, top_y),     # dot 1
                (left_x, mid_y),     # dot 2
                (left_x, bottom_y),  # dot 3
                (right_x, top_y),    # dot 4
                (right_x, mid_y),    # dot 5
                (right_x, bottom_y)  # dot 6
            ]

            for idx, bit in enumerate(bit_pattern):
                if bit != "1":
                    continue
                px, py = positions[idx]
                dots.append({
                    "x": self._clamp(px, 0.0, max(float(image_width - 1), 0.0)),
                    "y": self._clamp(py, 0.0, max(float(image_height - 1), 0.0)),
                    "confidence": confidence
                })

        return dots

    @staticmethod
    def _clamp(value: float, lower: float, upper: float) -> float:
        return max(lower, min(value, upper))

    def _bitstring_to_unicode(self, bit_pattern: str) -> Optional[str]:
        if not self._is_6dot_bitstring(bit_pattern):
            return None

        codepoint = 0x2800
        dot_values = (0x1, 0x2, 0x4, 0x8, 0x10, 0x20)
        for idx, bit in enumerate(bit_pattern):
            if bit == "1":
                codepoint += dot_values[idx]
        return chr(codepoint)
