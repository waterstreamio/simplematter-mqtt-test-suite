#!/bin/sh
set -e
SCRIPT_DIR=`realpath $(dirname "$0")`
PROJECT_DIR=$SCRIPT_DIR/../..

cd $PROJECT_DIR
sbt assembly

MQTT_TEST_SUITE_VERSION=`sbt -Dsbt.supershell=false -error "print version"`

LOCAL_MQTT_TEST_SUITE_IMAGE=simplematter-mqtt-test-suite

docker build --build-arg MQTT_TEST_SUITE_VERSION=${MQTT_TEST_SUITE_VERSION} -t $LOCAL_MQTT_TEST_SUITE_IMAGE:$MQTT_TEST_SUITE_VERSION -f $PROJECT_DIR/Dockerfile $PROJECT_DIR
docker tag $LOCAL_MQTT_TEST_SUITE_IMAGE:$MQTT_TEST_SUITE_VERSION $LOCAL_MQTT_TEST_SUITE_IMAGE:latest
