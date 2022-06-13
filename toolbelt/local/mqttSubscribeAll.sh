#!/bin/sh

set -e
SCRIPT_DIR=`realpath $(dirname "$0")`
PROJECT_DIR=$SCRIPT_DIR/..
cd $PROJECT_DIR

mosquitto_sub -h localhost -p 1883 -t '#' -v -q 2
