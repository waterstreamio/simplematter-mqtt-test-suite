terraform {
  required_providers {
    kubernetes = {
    }
    aws = {
    }
  }
}

provider "aws" {
  region = var.region
  access_key = var.aws_access_key
  secret_key = var.aws_secret_key
}

module "create_eks" {
  source = "./create_eks"

  cluster_name = var.cluster_name
  kubernetes_version = var.kubernetes_version
  default_k8s_node_group_instance_type = var.default_k8s_node_group_instance_type
  default_k8s_node_group_min_size = var.default_k8s_node_group_min_size
  default_k8s_node_group_max_size = var.default_k8s_node_group_max_size
  fargate_namespaces = var.fargate_namespaces
  vpc_name = var.vpc_name
  vpc_cidr = var.vpc_cidr
}

module "init_k8s" {
  source = "./init_k8s"
  depends_on = [module.create_eks]

  region = var.region
  kube_conf = module.create_eks.kube_conf
  base_aws_auth_configmap_yaml = module.create_eks.aws_auth_configmap_yaml
  additional_admins = var.additional_admins
}

data aws_eks_cluster "default" {
  name = var.cluster_name
  depends_on = [module.create_eks]
}

data aws_eks_cluster_auth "default" {
  name = var.cluster_name
  depends_on = [module.create_eks]
}

provider "kubernetes" {
  host = data.aws_eks_cluster.default.endpoint
  cluster_ca_certificate = base64decode(data.aws_eks_cluster.default.certificate_authority[0].data)
  token                  = data.aws_eks_cluster_auth.default.token
}

resource "local_file" "kubeconfig_out" {
  content  = module.create_eks.kube_conf
  filename = "${path.module}/../kube_config"
}

output "kube_config_path" {
  value = "${path.module}/../kube_config"
}