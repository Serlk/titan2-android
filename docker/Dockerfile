FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    bc bison flex make gcc libc6-dev git ca-certificates python3 python3-pip cpio kmod lz4 xz-utils gzip \
    libssl-dev libelf-dev libncurses-dev rsync zip unzip file perl device-tree-compiler dwarves \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work
