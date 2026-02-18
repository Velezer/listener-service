# Base Rust >=1.86
FROM rust:1.86-bullseye

# Install dependencies + OpenJDK 17
RUN apt-get update && apt-get install -y \
    unzip wget git cmake build-essential pkg-config curl zip openjdk-17-jdk ca-certificates && \
    update-ca-certificates && rm -rf /var/lib/apt/lists/*

# Android SDK/NDK
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools/bin:$PATH:$ANDROID_SDK_ROOT/platform-tools
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# Download Android command-line tools
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools && \
    wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -O cmdline-tools.zip && \
    unzip cmdline-tools.zip -d $ANDROID_SDK_ROOT/cmdline-tools && rm cmdline-tools.zip

# Accept licenses + install SDK/NDK
RUN yes | $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools/bin/sdkmanager --licenses
RUN $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools/bin/sdkmanager "platform-tools" "platforms;android-33" "ndk;25.2.9519653"

# Rust Android target + cargo-ndk
RUN rustup target add aarch64-linux-android
RUN cargo install cargo-ndk --locked

WORKDIR /workspace
