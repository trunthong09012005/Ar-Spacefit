# CPU-only distribution build; apt verifies Ubuntu's signed repository metadata.
# Only this Dockerfile enters the build context (no source, captures or secrets).
FROM ubuntu:24.04@sha256:f610ab94648195aa356059f5b41d6085c9d4d903c072430cdd1af7bdb646106b
LABEL org.opencontainers.image.title="AR-SpaceFit COLMAP CPU runtime" \
      org.opencontainers.image.version="3.9.1-2build2" \
      org.opencontainers.image.source="https://packages.ubuntu.com/noble/colmap" \
      io.ar-spacefit.owner="colmap-runtime"
RUN apt-get update && apt-get install -y --no-install-recommends colmap=3.9.1-2build2 \
    && rm -rf /var/lib/apt/lists/*
ENV QT_QPA_PLATFORM="offscreen" OMP_NUM_THREADS="4" OPENBLAS_NUM_THREADS="1"
WORKDIR /workspace
CMD ["colmap", "-h"]
