# LineageOS/AOSP build (packages as in the LineageOS Wiki "Install the build packages")
FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    bc bison build-essential ccache curl flex g++-multilib gcc-multilib git git-lfs gnupg gperf \
    imagemagick lib32readline-dev lib32z1-dev libelf-dev liblz4-tool lz4 libsdl1.2-dev libssl-dev \
    libxml2 libxml2-utils lzop pngcrush rsync schedtool squashfs-tools xsltproc zip zlib1g-dev \
    python3 python-is-python3 openjdk-17-jdk-headless ca-certificates unzip xz-utils sudo locales \
    && rm -rf /var/lib/apt/lists/* && locale-gen en_US.UTF-8 \
    && curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o /usr/local/bin/repo && chmod +x /usr/local/bin/repo
ENV LANG=en_US.UTF-8 USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache
WORKDIR /src
