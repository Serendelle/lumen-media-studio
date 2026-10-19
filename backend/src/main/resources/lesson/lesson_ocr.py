"""Lumen learning player: OCR of sampled video keyframes with RapidOCR (local, offline after the first model download).

Usage: python lesson_ocr.py <frames_dir>
Frames are read in name order. A frame that is visually the same as the previous one (slides, an unchanged editor)
is not OCR'd again: it reuses the previous result. Every result is one line on stdout:

    @@OCR {"file": "f00012.jpg", "dup": false, "lines": [{"text": "...", "score": 0.97, "box": [x0, y0, x1, y1]}]}

Anything else on stdout/stderr (library logging) is ignored by the caller.
"""
import json
import os
import sys


def thumb(cv2, path):
    img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if img is None:
        return None
    return cv2.resize(img, (48, 27), interpolation=cv2.INTER_AREA).astype("float32")


def main():
    frames_dir = sys.argv[1]
    import cv2
    from rapidocr import RapidOCR

    engine = RapidOCR()
    prev_thumb, prev_lines = None, []
    for name in sorted(os.listdir(frames_dir)):
        if not name.lower().endswith((".jpg", ".png")):
            continue
        path = os.path.join(frames_dir, name)
        t = thumb(cv2, path)
        dup = prev_thumb is not None and t is not None and float(abs(t - prev_thumb).mean()) < 2.0
        if dup:
            lines = prev_lines
        else:
            lines = []
            try:
                res = engine(path)
                txts = res.txts or ()
                scores = res.scores or ()
                boxes = res.boxes if res.boxes is not None else ()
                for i, text in enumerate(txts):
                    score = float(scores[i]) if i < len(scores) else 0.0
                    if score < 0.5 or not str(text).strip():
                        continue
                    b = boxes[i] if i < len(boxes) else None
                    box = None
                    if b is not None:
                        xs = [float(p[0]) for p in b]
                        ys = [float(p[1]) for p in b]
                        box = [round(min(xs)), round(min(ys)), round(max(xs)), round(max(ys))]
                    lines.append({"text": str(text), "score": round(score, 3), "box": box})
            except Exception as e:  # one unreadable frame must not stop the lesson
                print("@@ERR " + json.dumps({"file": name, "error": str(e)[:200]}, ensure_ascii=False), flush=True)
            prev_lines = lines
        prev_thumb = t if t is not None else prev_thumb
        print("@@OCR " + json.dumps({"file": name, "dup": dup, "lines": lines}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
