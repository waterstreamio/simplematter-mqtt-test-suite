variable "region" {
  default = "eu-central-1"
  type = string
}

variable "additional_admins" {
  description = "Additional AWS users that should be able to manage the EKS cluster. Mapping in aws-auth ConfigMap is created for them with group 'system:masters'"
  #Example:
  # {"userarn": "arn:aws:iam::111122223333:user/admin", "username": "admin"}
  type = list(map(string))
  default = []
}


variable "kube_conf" {
  type = string
}

variable "base_aws_auth_configmap_yaml" {
  type = string
}