FROM python:3.12-alpine

WORKDIR /app
COPY server.py /app/server.py

ENV PYTHONUNBUFFERED=1 \
    PORT=8088 \
    DATA_DIR=/data

VOLUME ["/data"]
EXPOSE 8088

CMD ["python3", "/app/server.py"]
