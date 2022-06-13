data "aws_eks_cluster_auth" "this" {
  name = module.eks.cluster_id
}

output "kube_conf" {
  value = yamlencode({
    apiVersion      = "v1"
    kind            = "Config"
    current-context = "terraform"
    clusters        = [
      {
        name    = module.eks.cluster_id
        cluster = {
          certificate-authority-data = module.eks.cluster_certificate_authority_data
          server                     = module.eks.cluster_endpoint
        }
      }
    ]
    contexts        = [
      {
        name    = "terraform"
        context = {
          cluster = module.eks.cluster_id
          user    = "terraform"
        }
      }
    ]
    users           = [
      {
        name = "terraform"
        user = {
          token = data.aws_eks_cluster_auth.this.token
        }
      }
    ]
  })

}

output "aws_auth_configmap_yaml" {
  value = module.eks.aws_auth_configmap_yaml
}