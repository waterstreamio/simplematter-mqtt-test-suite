#!/bin/sh
set -e
SCRIPT_DIR=`realpath $(dirname "$0")`

cd $SCRIPT_DIR

#terraform apply --target module.create_eks --auto-approve
terraform apply --target local_file.kubeconfig_out --auto-approve
