#!/bin/sh

set -e
SCRIPT_DIR=`realpath $(dirname "$0")`
PROJECT_DIR=$SCRIPT_DIR/../..
cd $PROJECT_DIR

IMAGE_NAME=simplematter/simplematter-mqtt-test-suite:0.0.4
#IMAGE_NAME=simplematter/simplematter-mqtt-test-suite:0.0.5-SNAPSHOT
#IMAGE_NAME=simplematter-mqtt-test-suite
CONTAINER_NAME=simplematter-mqtt-test-suite-agg
echo Starting $CONTAINER_NAME from $IMAGE_NAME


export MQTT_LOAD_STATS_PRINTING_INTERVAL_MILLIS=10000

export MQTT_LOAD_STATS_PORT=8080

export MQTT_LOAD_HAZELCAST_PORT=5801
export MQTT_LOAD_HAZELCAST_SEED_MEMBERS=localhost:5801

#interactive
INTERACTIVE=-it
#non-interactive
#INTERACTIVITY=-d

JMX_OPTIONS=""

DEBUG_OPTIONS=""

#No cleanup
#CLEANUP=""
#Remove container automatically when completed
CLEANUP="--rm"

docker run $INTERACTIVITY $CLEANUP $JMX_OPTIONS $DEBUG_OPTIONS \
     -e MQTT_LOAD_STATS_PRINTING_INTERVAL_MILLIS=$MQTT_LOAD_STATS_PRINTING_INTERVAL_MILLIS \
     -e MQTT_LOAD_STATS_PORT=$MQTT_LOAD_STATS_PORT \
     -e MQTT_LOAD_HAZELCAST_PORT=$MQTT_LOAD_HAZELCAST_PORT \
     -e MQTT_LOAD_HAZELCAST_SEED_MEMBERS=$MQTT_LOAD_HAZELCAST_SEED_MEMBERS \
     -e MQTT_TEST_SUITE_AGGREGATOR=true \
     --network host \
     --name $CONTAINER_NAME $IMAGE_NAME
