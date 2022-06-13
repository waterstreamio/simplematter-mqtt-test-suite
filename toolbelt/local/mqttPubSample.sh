#!/bin/sh

set -e
SCRIPT_DIR=`realpath $(dirname "$0")`
PROJECT_DIR=$SCRIPT_DIR/../..
cd $PROJECT_DIR

mosquitto_pub -h localhost -p 1883 -t 'sample_topic' -q 2 -m "Hello, world"
