# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------------------
# 1. crypto-build: PS3Dec against a pinned, statically linked mbedTLS 4.x.
#    Debian trixie only packages mbedTLS 3.x, so 4.x is built from the release tarball.
# ---------------------------------------------------------------------------------------
FROM debian:trixie-slim AS crypto-build

ARG MBEDTLS_VERSION=4.2.0
ARG MBEDTLS_SHA256=2bed9d713b4668f76553b097e72b8aa30bc8f112a940d7ae228d524bbde6ffea

RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential cmake ninja-build python3 curl ca-certificates bzip2 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /tmp/mbedtls
RUN curl -fsSL -o mbedtls.tar.bz2 \
      "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-${MBEDTLS_VERSION}/mbedtls-${MBEDTLS_VERSION}.tar.bz2" \
 && echo "${MBEDTLS_SHA256}  mbedtls.tar.bz2" | sha256sum -c - \
 && tar xjf mbedtls.tar.bz2 \
 && cmake -S "mbedtls-${MBEDTLS_VERSION}" -B build -G Ninja \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX=/opt/mbedtls \
      -DENABLE_PROGRAMS=OFF \
      -DENABLE_TESTING=OFF \
      -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
      -DUSE_STATIC_MBEDTLS_LIBRARY=ON \
 && cmake --build build \
 && cmake --install build

WORKDIR /src
COPY CMakeLists.txt ./
COPY src ./src
RUN cmake -S . -B /build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH=/opt/mbedtls \
 && cmake --build /build \
 && install -D /build/Release/PS3Dec /out/ps3dec

# ---------------------------------------------------------------------------------------
# 2. web-build: the React/Vite frontend.
#    Node 25+ no longer ships corepack, so pnpm is installed with npm.
# ---------------------------------------------------------------------------------------
FROM node:26-trixie-slim AS web-build

ARG PNPM_VERSION=12.6.0
RUN npm install -g "pnpm@${PNPM_VERSION}"

WORKDIR /web
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

# ---------------------------------------------------------------------------------------
# 3. py-deps: backend dependencies in a venv (uv stays out of the final image).
# ---------------------------------------------------------------------------------------
FROM python:3.14-slim-trixie AS py-deps

ARG UV_VERSION=0.11.28
RUN pip install --no-cache-dir "uv==${UV_VERSION}"

WORKDIR /app
COPY backend/pyproject.toml backend/uv.lock ./
RUN UV_PROJECT_ENVIRONMENT=/opt/venv uv sync --frozen --no-dev --no-install-project --no-cache

# ---------------------------------------------------------------------------------------
# 4. runtime: Debian trixie + Python, no compilers, no Node.
# ---------------------------------------------------------------------------------------
FROM python:3.14-slim-trixie AS runtime

RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --gid 1000 ps3dec \
 && useradd --uid 1000 --gid 1000 --no-create-home --shell /usr/sbin/nologin ps3dec \
 && mkdir -p /data/iso /data/keys /data/output \
 && chown 1000:1000 /data/iso /data/keys /data/output

COPY --from=crypto-build /out/ps3dec /usr/local/bin/ps3dec
COPY --from=py-deps /opt/venv /opt/venv
COPY backend/app /app/app
COPY --from=web-build /web/dist /app/static

ENV PATH="/opt/venv/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PORT=8000

WORKDIR /app
USER 1000:1000
EXPOSE 8000
VOLUME ["/data/output"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/api/health' % os.environ.get('PORT', '8000'), timeout=3)"]

# One process, one worker: job state is held in memory.
CMD ["python", "-m", "app"]
