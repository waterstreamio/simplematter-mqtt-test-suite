#!/bin/sh
set -e
SCRIPT_DIR=`realpath $(dirname "$0")`

cd $SCRIPT_DIR


terraform state rm module.init_k8s.kubernetes_config_map.aws-logging || true
terraform state rm module.init_k8s.kubernetes_namespace.aws-observability || true
terraform state rm module.init_k8s.local_file.kubeconfig_out || true
terraform state rm module.init_k8s.null_resource.patch_aws_auth || true

terraform destroy --auto-approve
