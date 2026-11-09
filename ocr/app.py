"""CPU PaddleOCR API. Only reachable from the application network."""
import base64
import binascii
import io
import threading
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI, HTTPException
from fastapi.concurrency import run_in_threadpool
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field
from paddleocr import PaddleOCR

model = None
inference_lock = threading.Lock()
Image.MAX_IMAGE_PIXELS = 20_000_000

@asynccontextmanager
async def lifespan(app: FastAPI):
    global model
    model = await run_in_threadpool(lambda: PaddleOCR(
        device="cpu", use_doc_orientation_classify=False, use_doc_unwarping=False,
        use_textline_orientation=False, text_detection_model_name="PP-OCRv5_mobile_det",
        text_recognition_model_name="PP-OCRv5_mobile_rec", cpu_threads=2,
    ))
    yield

app = FastAPI(lifespan=lifespan)

class OcrRequest(BaseModel):
    image: str = Field(max_length=11_000_000)

@app.get("/health")
def health():
    return {"ready": model is not None}

def recognize(encoded: str):
    try:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > 8_000_000:
            raise HTTPException(413, "Image too large")
        with Image.open(io.BytesIO(raw)) as source:
            source.load()
            if source.width * source.height > Image.MAX_IMAGE_PIXELS:
                raise HTTPException(413, "Image dimensions too large")
            source.thumbnail((2000, 2000))
            pixels = np.asarray(source.convert("RGB"))[:, :, ::-1].copy()
    except (binascii.Error, UnidentifiedImageError, OSError, Image.DecompressionBombError):
        raise HTTPException(400, "Invalid image")
    with inference_lock:
        results = model.predict(pixels)
        lines = [text for result in results for text in result["rec_texts"]]
    return {"text": "\n".join(lines)}

@app.post("/ocr")
async def ocr(request: OcrRequest):
    return await run_in_threadpool(recognize, request.image)
