#!/bin/sh

set -e
SCRIPT_DIR=`realpath $(dirname "$0")`

MOSQUITTO_VERSION=2.0.11
#MOSQUITTO_VERSION=1.6.15
#MOSQUITTO_VERSION=1.5.11

docker run -it -p 1883:1883 -p 9001:9001 -v $SCRIPT_DIR/testMosquitto.conf:/mosquitto/config/mosquitto.conf:ro eclipse-mosquitto:$MOSQUITTO_VERSION
