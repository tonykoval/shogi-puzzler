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
    curl \
    gnupg \
    && curl -sL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# Copy the application fat JAR
COPY --from=app-builder /build/target/scala-2.13/shogi-puzzler-assembly-*.jar ./app.jar

# Install playwright browsers and their dependencies
RUN java -cp app.jar com.microsoft.playwright.CLI install chromium --with-deps

# Copy the engine from stage 1
COPY --from=engine-builder /tmp/YaneuraOu/source/YaneuraOu-by-gcc ./engine/yaneuraou_linux
RUN chmod +x ./engine/yaneuraou_linux

# Copy the eval folder from the repository
# Putting it inside engine/ folder so it's next to the binary
COPY eval/ ./engine/eval/

# Set environment variables
ENV ENGINE_PATH=engine/yaneuraou_linux

# Expose the port (Cask default is 8080)
EXPOSE 8080

# Run the application
CMD ["java", "-jar", "app.jar"]
