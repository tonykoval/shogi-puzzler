# Stage 1: Build YaneuraOu engine for Linux
FROM ubuntu:22.04 AS engine-builder

RUN apt-get update && apt-get install -y \
    make \
    g++ \
    git \
    && rm -rf /var/lib/apt/lists/*

RUN git clone https://github.com/yaneurao/YaneuraOu.git /tmp/YaneuraOu
WORKDIR /tmp/YaneuraOu/source

# Build for AVX2. Change TARGET_CPU if your server has different architecture.
# Using EVAL_NNUE and COMPACTION_OSL as commonly used in tournament builds.
RUN make -j$(nproc) COMPILER=g++ TARGET_CPU=avx2 EXTRA_CPPFLAGS="-DCOMPACTION_OSL -DEVAL_NNUE"

# Stage 2: Build Scala application
FROM eclipse-temurin:17-jdk-alpine AS app-builder

# Install sbt
RUN apk add --no-cache bash curl \
    && curl -L https://github.com/sbt/sbt/releases/download/v1.11.7/sbt-1.11.7.tgz | tar -xz -C /usr/local \
    && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt

WORKDIR /build
COPY . .

# Compile and package the application
RUN sbt assembly

# Stage 3: Final Runtime Image
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Install Playwright dependencies
RUN apt-get update && apt-get install -y \
    libglib2.0-0 \
    libnss3 \
    libnspr4 \
    libdbus-1-3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libatspi2.0-0 \
    libx11-6 \
    libxcomposite1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libdrm2 \
    libxcb1 \
    libxkbcommon0 \
    libasound2 \
    libcups2 \
    libpango-1.0-0 \
    libcairo2 \
    libx11-xcb1 \
    libxcursor1 \
    libgtk-3-0 \
    libpangocairo-1.0-0 \
    libcairo-gobject2 \
    libgdk-pixbuf-2.0-0 \
    libgstreamer1.0-0 \
    libgstreamer-plugins-base1.0-0 \
    libatomic1 \
    libxslt1.1 \
    libwoff1 \
    libvpx7 \
    libevent-2.1-7 \
    libopus0 \
    libflite1 \
    libwebpdemux2 \
    libavif13 \
    libharfbuzz-icu0 \
    libwebpmux3 \
    libenchant-2-2 \
    libsecret-1-0 \
    libhyphen0 \
    libmanette-0.2-0 \
    libgles2 \
    libx264-dev \
    && rm -rf /var/lib/apt/lists/*

# Copy the engine from stage 1
COPY --from=engine-builder /tmp/YaneuraOu/source/YaneuraOu-by-gcc ./engine/yaneuraou_linux
RUN chmod +x ./engine/yaneuraou_linux

# Copy the eval folder from the repository
# Putting it inside engine/ folder so it's next to the binary
COPY eval/ ./engine/eval/

# Copy the application fat JAR
COPY --from=app-builder /build/target/scala-2.13/shogi-puzzler-assembly-*.jar ./app.jar

# Set environment variables
ENV ENGINE_PATH=engine/yaneuraou_linux

# Expose the port (Cask default is 8080)
EXPOSE 8080

# Run the application
CMD ["java", "-jar", "app.jar"]
