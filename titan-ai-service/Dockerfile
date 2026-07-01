FROM python:3.11-slim

ARG TARGETARCH
WORKDIR /app

# Install wget for grpc_health_probe download
RUN apt-get update && apt-get install -y --no-install-recommends wget && \
    # Normalize arch: TARGETARCH can be 'amd64' or 'arm64', probe uses same naming
    ARCH="${TARGETARCH:-amd64}" && \
    wget -qO /bin/grpc_health_probe \
      "https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/v0.4.24/grpc_health_probe-linux-${ARCH}" && \
    chmod +x /bin/grpc_health_probe && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Python dependencies first (layer cache)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Generate gRPC stubs from proto file
COPY risk_engine.proto .
RUN mkdir -p protos && \
    python -m grpc_tools.protoc -I. \
        --python_out=./protos \
        --grpc_python_out=./protos \
        risk_engine.proto && \
    touch protos/__init__.py && \
    # Fix generated import: grpc_tools generates 'import risk_engine_pb2'
    # but we add protos/ to sys.path in main.py so bare import works fine.
    # Verify the generated files exist
    ls -la protos/

# Copy application
COPY main.py .

EXPOSE 50051

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD ["/bin/grpc_health_probe", "-addr=:50051"]

CMD ["python", "main.py"]
