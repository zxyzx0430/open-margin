FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY backend/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt && \
    mkdir -p /data/finance && chown -R www-data:www-data /data/finance
COPY backend /app
USER 33:33
EXPOSE 8767
CMD ["uvicorn","app:app","--host","0.0.0.0","--port","8767","--workers","1"]
