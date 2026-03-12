from fastapi import FastAPI
from app.api.vision_controller import router as vision_router

app = FastAPI(title="BrailleAI Vision Service")

app.include_router(vision_router, prefix="/vision")

@app.get("/")
def root():
    return {"status": "ok", "service": "brailleai-vision"}