# The official v2.4.0 release workflow builds Ubuntu x64 without CUDA.
# Only this Dockerfile and the checksum-verified release archive enter the
# temporary build context; no workspace, .env, input video or DB is uploaded.
FROM ubuntu:24.04@sha256:f610ab94648195aa356059f5b41d6085c9d4d903c072430cdd1af7bdb646106b AS unpack
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
COPY OpenMVS_Ubuntu_x64.zip /tmp/openmvs.zip
RUN echo '7104ae1ddd6ca38fbca9e0e4a70b20af59e21e0b497eb7181c864fbf38ca8d00  /tmp/openmvs.zip' | sha256sum -c - \
    && unzip -q /tmp/openmvs.zip -d /extract \
    && mkdir -p /selected/bin \
    && for tool in InterfaceCOLMAP DensifyPointCloud ReconstructMesh RefineMesh TextureMesh; do \
         binary="$(find /extract -type f -name "$tool")"; \
         test -n "$binary" && test "$(printf '%s\n' "$binary" | wc -l)" -eq 1 \
           && cp "$binary" "/selected/bin/$tool" \
           && chmod 0555 "/selected/bin/$tool" || exit 1; \
       done

FROM ubuntu:24.04@sha256:f610ab94648195aa356059f5b41d6085c9d4d903c072430cdd1af7bdb646106b
LABEL org.opencontainers.image.title="AR-SpaceFit OpenMVS CPU runtime" \
      org.opencontainers.image.version="2.4.0" \
      org.opencontainers.image.source="https://github.com/cdcseacave/openMVS" \
      org.opencontainers.image.revision="58117204c86bbb11a0b25b26a8987676cf11274d" \
      org.opencontainers.image.licenses="AGPL-3.0" \
      io.ar-spacefit.owner="openmvs-runtime" \
      io.ar-spacefit.release.sha256="7104ae1ddd6ca38fbca9e0e4a70b20af59e21e0b497eb7181c864fbf38ca8d00"
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=unpack /selected/bin/ /opt/openmvs/bin/
ENV PATH="/opt/openmvs/bin:${PATH}" \
    OMP_NUM_THREADS="4"
WORKDIR /workspace
# No ENTRYPOINT wrapper: Docker receives the tool name and exact argv directly.
CMD ["InterfaceCOLMAP", "-h"]
