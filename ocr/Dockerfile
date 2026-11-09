FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends libglib2.0-0 libgl1 libgomp1 && rm -rf /var/lib/apt/lists/*
WORKDIR /service
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY constraints.txt .
RUN pip install --no-cache-dir -r constraints.txt
COPY app.py .
ENV HOME=/data OMP_NUM_THREADS=2
RUN useradd --uid 10001 --create-home ocr && mkdir /data && chown ocr:ocr /data
USER ocr
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
