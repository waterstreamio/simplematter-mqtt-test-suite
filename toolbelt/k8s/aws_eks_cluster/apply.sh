#!/bin/sh
set -e
SCRIPT_DIR=`realpath $(dirname "$0")`

cd $SCRIPT_DIR

terraform apply --auto-approve
