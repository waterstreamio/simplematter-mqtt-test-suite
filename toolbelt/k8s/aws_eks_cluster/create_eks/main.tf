terraform {
  required_providers {
    aws = {
    }
  }
}

module "eks" {
  source          = "terraform-aws-modules/eks/aws"
  version         = "18.6.0"
  cluster_name    = var.cluster_name
  cluster_version = var.kubernetes_version
  subnet_ids         = module.vpc.private_subnets

  vpc_id = module.vpc.vpc_id

  eks_managed_node_group_defaults = {
#    root_volume_type = "gp2"
  }

  eks_managed_node_groups = {
    # You require a node group to schedule coredns which is critical for running correctly internal DNS.
    # If you want to use only fargate you must follow docs `(Optional) Update CoreDNS`
    # available under https://docs.aws.amazon.com/eks/latest/userguide/fargate-getting-started.html
    default = {
      create_launch_template = false
      launch_template_name = ""
      name                          = "worker-group-1"
      instance_types                = [var.default_k8s_node_group_instance_type]
#      instance_type                = var.default_k8s_node_group_instance_type
      desired_size                  = var.default_k8s_node_group_min_size
      min_size                      = var.default_k8s_node_group_min_size
      max_size                      = var.default_k8s_node_group_max_size
    }
  }

# Uncomment to enable Fargate - takes longer to create K8s cluster, but more flexible with the workload size
#  fargate_profiles = {
#    default = {
#      name = "default"
#      selectors = concat(
#        [{
#          namespace = "kube-system"
#          labels = { k8s-app = "kube-dns" }
#        }],
#        [for n in var.fargate_namespaces:
#        {
#           namespace = n
#        }])
#
#      timeouts = {
#        create = "10m"
#        delete = "10m"
#      }
#    }
#  }
}
