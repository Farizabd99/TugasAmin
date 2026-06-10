#!/usr/bin/env sh

APP_HOME=$(cd "$(dirname "$0")" || exit 1; pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$JAR" ]; then
  echo "Missing gradle/wrapper/gradle-wrapper.jar"
  echo "Install Gradle and run: gradle wrapper --gradle-version 8.10.2"
  exit 1
fi

exec java -jar "$JAR" "$@"
