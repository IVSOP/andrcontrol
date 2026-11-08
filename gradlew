#!/bin/sh
# Small POSIX launcher for the official, pinned Gradle wrapper.
set -eu
APP_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD=java
fi
exec "$JAVA_CMD" -classpath "$APP_DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"

