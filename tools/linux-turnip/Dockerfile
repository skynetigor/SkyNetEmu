FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git curl build-essential cmake ninja-build python3 python3-venv \
    pkg-config bison flex glslang-tools zip unzip zstd xz-utils patch libexpat1-dev \
    gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \
 && python3 -m venv /opt/turnip-tools \
 && /opt/turnip-tools/bin/pip install --no-cache-dir meson==1.9.1 mako==1.3.10 PyYAML==6.0.3 packaging==25.0 \
 && rm -rf /var/lib/apt/lists/*
ENV PATH=/opt/turnip-tools/bin:$PATH
WORKDIR /work
