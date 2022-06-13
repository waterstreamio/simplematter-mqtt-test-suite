terraform {
  required_providers {
    kubernetes = {
    }
  }
}

resource "kubernetes_namespace" "aws-observability" {
  metadata {
    name = "aws-observability"
    labels = {
      "aws-observability": "enabled"
    }
  }
}

resource "kubernetes_config_map" "aws-logging" {
  metadata {
    name = "aws-logging"
    namespace = kubernetes_namespace.aws-observability.metadata.0.name
  }

  data = {
    "output.conf" = <<EOF
[OUTPUT]
        Name cloudwatch_logs
        Match   *
        region  ${var.region}
        log_group_name fluent-bit-cloudwatch
        log_stream_prefix from-fluent-bit-
        auto_create_group true
        log_key log
EOF

    "parsers.conf" = <<EOF
[PARSER]
    Name crio
    Format Regex
    Regex ^(?<time>[^ ]+) (?<stream>stdout|stderr) (?<logtag>P|F) (?<log>.*)$
    Time_Key    time
    Time_Format %Y-%m-%dT%H:%M:%S.%L%z
EOF

    "filters.conf" = <<EOF
[FILTER]
    Name parser
    Match *
    Key_name log
    Parser crio
EOF

  }
}

locals {
  aws_auth_configmap_yaml = <<-EOF
${chomp(var.base_aws_auth_configmap_yaml)}
  mapUsers: |
%{ for u in var.additional_admins ~}
      - userarn: ${u.userarn}
        username: ${u.username}
        groups:
          - system:masters
%{ endfor ~}
  EOF

}

#resource "local_file" "aws_auth_dbg" {
#  content  = local.aws_auth_configmap_yaml
#  filename = "${path.module}/../aws_auth_dbg.yml"
#}


#Need to patch aws-auth with the external `kubectl` program because Terraform fails to create the aws-auth ConfigMap because it already exists
resource "null_resource" "patch_aws_auth" {
  count = length(var.additional_admins) == 0 ? 0 : 1

  triggers = {
    kubeconfig = base64encode(var.kube_conf)
    cmd_patch  = "kubectl patch configmap/aws-auth --patch \"${local.aws_auth_configmap_yaml}\" --type merge -n kube-system --kubeconfig <(echo $KUBECONFIG | base64 --decode)"

  }

  provisioner "local-exec" {
    interpreter = ["/bin/bash", "-c"]
    environment = {
      KUBECONFIG = self.triggers.kubeconfig
    }
    command = self.triggers.cmd_patch
  }
}
