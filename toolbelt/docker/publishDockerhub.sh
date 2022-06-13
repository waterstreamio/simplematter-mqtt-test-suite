#!/bin/sh
set -e
SCRIPT_DIR=`realpath $(dirname "$0")`
PROJECT_DIR=$SCRIPT_DIR/../..

$SCRIPT_DIR/buildDocker.sh

cd $PROJECT_DIR
MQTT_TEST_SUITE_VERSION=`sbt -Dsbt.supershell=false -error "print version"`

LOCAL_MQTT_TEST_SUITE_IMAGE=simplematter-mqtt-test-suite
DH_MQTT_TEST_SUITE_IMAGE=simplematter/simplematter-mqtt-test-suite

echo "Pushing to $DH_MQTT_TEST_SUITE_IMAGE tag $MQTT_TEST_SUITE_VERSION"
docker tag $LOCAL_MQTT_TEST_SUITE_IMAGE:$MQTT_TEST_SUITE_VERSION $DH_MQTT_TEST_SUITE_IMAGE:$MQTT_TEST_SUITE_VERSION
docker push $DH_MQTT_TEST_SUITE_IMAGE:$MQTT_TEST_SUITE_VERSION

echo "$MQTT_TEST_SUITE_VERSION" | grep -qE "SNAPSHOT" || NOT_SNAPSHOT=$?
echo ISNS=$NOT_SNAPSHOT
if [ $NOT_SNAPSHOT ];
then
  echo "Pushing to $DH_DH_MQTT_TEST_SUITE_IMAGE tag latest"
  docker tag $LOCAL_MQTT_TEST_SUITE_IMAGE:latest $DH_MQTT_TEST_SUITE_IMAGE:latest
  docker push $DH_MQTT_TEST_SUITE_IMAGE:latest
else
  echo Snapshot - skipping latest tag
fi

echo Push to $DH_MQTT_TEST_SUITE_IMAGE completed
date


