#!/bin/sh

set -e
SCRIPT_DIR=`realpath $(dirname "$0")`
PROJECT_DIR=$SCRIPT_DIR/../..
cd $PROJECT_DIR

#sbt assembly
#TESTSUITE_JAR=$PROJECT_DIR/target/scala-3.0.0/simplematter-mqtt-test-suite-assembly-0.0.1-SNAPSHOT.jar
#java -jar $TESTSUITE_JAR

export MQTT_LOAD_STATS_PRINTING_INTERVAL_MILLIS=10000

export MQTT_LOAD_STATS_PORT=8080

export MQTT_LOAD_HAZELCAST_PORT=5801
export MQTT_LOAD_HAZELCAST_SEED_MEMBERS=localhost:5801

sbt "runMain io.simplematter.mqtttestsuite.TestSuiteStatsAggregator"
