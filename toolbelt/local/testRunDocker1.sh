#!/bin/sh

set -e
SCRIPT_DIR=`realpath $(dirname "$0")`
PROJECT_DIR=$SCRIPT_DIR/../..

SCENARIO_CFG=$1
if [ -z "$SCENARIO_CFG" ]
then
  echo Scenario config not specified
  exit 1
else
  #convert to the absolute path
  export SCENARIO_CFG_ABSOLUTE=`realpath $SCENARIO_CFG`
fi

cd $PROJECT_DIR
CONTAINER_NAME_SUFFIX=$2

IMAGE_NAME=simplematter/simplematter-mqtt-test-suite:0.0.4
#IMAGE_NAME=simplematter/simplematter-mqtt-test-suite:0.0.5-SNAPSHOT
CONTAINER_NAME=simplematter-mqtt-test-suite-runner-$CONTAINER_NAME_SUFFIX
echo Starting $CONTAINER_NAME from $IMAGE_NAME

#export MQTT_LOAD_STATS_INTERVAL_MILLIS=2000

#export MQTT_LOAD_STATS_PORT=8080
export MQTT_LOAD_STATS_PORT=

export MQTT_LOAD_HAZELCAST_SEED_MEMBERS="localhost:5801"

#export MQTT_LOAD_COMPLETION_TIMEOUT_MILLIS=10000
export MQTT_LOAD_COMPLETION_TIMEOUT_MILLIS=20000

export MQTT_LOAD_STATS_INDIVIDUAL_MESSAGE_TRACKING=true

#export MQTT_LOAD_EXPECTED_RUNNER_NODES_COUNT=1
export MQTT_LOAD_EXPECTED_RUNNER_NODES_COUNT=2
export MQTT_LOAD_EXPECTED_AGGREGATOR_NODES_COUNT=1


#sbt -Dhazelcast.diagnostics.enabled=false "runMain io.simplematter.mqtttestsuite.TestSuiteRunner"

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
     -e MQTT_LOAD_HAZELCAST_SEED_MEMBERS=$MQTT_LOAD_HAZELCAST_SEED_MEMBERS \
     -e MQTT_LOAD_EXPECTED_RUNNER_NODES_COUNT=$MQTT_LOAD_EXPECTED_RUNNER_NODES_COUNT \
     -e MQTT_LOAD_EXPECTED_AGGREGATOR_NODES_COUNT=$MQTT_LOAD_EXPECTED_AGGREGATOR_NODES_COUNT \
     -e MQTT_LOAD_COMPLETION_TIMEOUT_MILLIS=$MQTT_LOAD_COMPLETION_TIMEOUT_MILLIS \
     -e MQTT_LOAD_SCENARIO_CONFIG_PATH=/etc/mqtt_test_scenario.conf \
     -v $SCENARIO_CFG_ABSOLUTE:/etc/mqtt_test_scenario.conf \
     --network host \
     --name $CONTAINER_NAME $IMAGE_NAME
