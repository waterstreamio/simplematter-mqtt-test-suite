variable project {
  type = string
  description = "todo-add-your-gcp-project-name"
}

variable "region" {
  type = string
  default = "europe-west1"
}

variable "zone" {
  type = string
  default = "europe-west1-b"
}

variable "cluster_name" {
  type = string
  default = "mqtt-test-suite"
}

variable "k8s_version" {
  #See the versions available here: https://cloud.google.com/kubernetes-engine/docs/release-notes
  default = "latest"
}

variable "vpc_cidr_block" {
  description = "The IP address range of the VPC in CIDR notation. A prefix of /16 is recommended. Do not use a prefix higher than /27."
  type        = string
  default     = "10.3.0.0/16"
}
