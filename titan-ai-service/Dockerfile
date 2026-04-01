FROM python:3.11-slim
ARG TARGETARCH
WORKDIR /app
RUN apt-get update && apt-get install -y wget && \
    ARCH="${TARGETARCH:-amd64}"; \
    wget -qO /bin/grpc_health_probe "https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/v0.4.24/grpc_health_probe-linux-${ARCH}" && \
    chmod +x /bin/grpc_health_probe && \
    apt-get clean && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY risk_engine.proto .
RUN mkdir -p protos && \
    python -m grpc_tools.protoc -I. --python_out=./protos --grpc_python_out=./protos risk_engine.proto && \
    touch protos/__init__.py
COPY main.py .
EXPOSE 50051
HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=3 \
    CMD ["/bin/grpc_health_probe", "-addr=:50051"]
CMD ["python", "main.py"]
