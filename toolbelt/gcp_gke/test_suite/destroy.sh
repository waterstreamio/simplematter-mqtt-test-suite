#!/bin/sh
set -e

echo Destroying the test resources
terraform destroy --auto-approve

date
