FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates git build-essential cmake ninja-build python3 \
    clang-18 llvm-18-dev libclang-18-dev libclang-cpp18-dev \
    libssl-dev libx11-dev libxrandr-dev libxcb1-dev \
    libc6-dev-amd64-cross libstdc++-13-dev-amd64-cross \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /work
