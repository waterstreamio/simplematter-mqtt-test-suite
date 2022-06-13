#!/bin/sh

LIB=/opt/simplematter-mqtt-test-suite
PROJECT_JAR=$LIB/simplematter-mqtt-test-suite-assembly-${MQTT_TEST_SUITE_VERSION}.jar
CLASSPATH=$PROJECT_JAR

if [ "$MQTT_TEST_SUITE_AGGREGATOR" = true ];
then
  echo Running aggregator
  MAIN_CLASS=io.simplematter.mqtttestsuite.TestSuiteStatsAggregator
else
  echo Running test executor
  MAIN_CLASS=io.simplematter.mqtttestsuite.TestSuiteRunner
fi

JAVA_MODULES="--add-modules java.se --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.management/sun.management=ALL-UNNAMED --add-opens jdk.management/com.sun.management.internal=ALL-UNNAMED"

#

java -cp $CLASSPATH $JAVA_MODULES $MQTT_TEST_SUITE_JAVA_OPTS $MAIN_CLASS

