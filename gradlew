#!/bin/sh

APP_HOME=$(cd "${0%/*}" 2>/dev/null && pwd -P)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
    JAVACMD=$JAVA_HOME/bin/java
else
    JAVACMD=java
fi

exec "$JAVACMD" $JAVA_OPTS $GRADLE_OPTS -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
