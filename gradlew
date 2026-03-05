#!/bin/sh
# Gradle start up script for UN*X

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
APP_HOME=`cd "${APP_HOME%/}" && pwd -P` || exit

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

exec "${JAVACMD:-java}" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
