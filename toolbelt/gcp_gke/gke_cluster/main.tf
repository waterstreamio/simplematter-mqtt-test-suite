provider "google" {
  credentials = file("../account.json")
  project = var.project
  region = var.region
}

# Use a random suffix to prevent overlap in network names
resource "random_string" "suffix" {
  length = 4
  special = false
  upper = false
}

resource "google_compute_network" "net" {
  name = "${var.cluster_name}-network-${random_string.suffix.result}"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subnet" {
  name = "${var.cluster_name}-subnetwork-${random_string.suffix.result}"
  network = google_compute_network.net.self_link
  ip_cidr_range = var.vpc_cidr_block
  region = var.region
}

resource "google_container_cluster" "cluster" {
  timeouts {
    delete = "120m"
  }

  name = var.cluster_name
  location = var.region
  min_master_version = var.k8s_version

  enable_autopilot = true

#  initial_node_count = 1

  network = google_compute_network.net.name
  subnetwork = google_compute_subnetwork.subnet.name


#  //Docs for the details: https://www.terraform.io/docs/providers/google/r/container_cluster.html#enable_private_nodes
#  private_cluster_config {
#    enable_private_nodes = true
#    enable_private_endpoint = false
#    master_ipv4_cidr_block = var.master_ipv4_cidr_block
#  }
#
#  ip_allocation_policy {
#
#  }

#  master_auth {
#    username = ""
#    password = ""
#
#    client_certificate_config {
#      issue_client_certificate = false
#    }
#  }

  /* Calico might be an option for improved ingress performance if we connect MQTT clients from the edge, currently not the case

  network_policy {
    enabled = true
    provider = "CALICO"
  }

  addons_config {
    network_policy_config {
      disabled = false
    }
  }*/
}


# Configure kubernetes provider with Oauth2 access token.
# https://registry.terraform.io/providers/hashicorp/google/latest/docs/data-sources/client_config
# This fetches a new token, which will expire in 1 hour.
data "google_client_config" "default" {
#  depends_on = [module.gke-cluster]
}

data "template_file" "kubeconfig" {
  template = file("${path.module}/kubeconfig-template.yaml")
  vars = {
#    cluster_ca = base64encode(google_container_cluster.cluster.master_auth[0].cluster_ca_certificate)
    cluster_ca = google_container_cluster.cluster.master_auth[0].cluster_ca_certificate
    endpoint = "https://${google_container_cluster.cluster.endpoint}"
    cluster_name = google_container_cluster.cluster.name
    cluster_token = data.google_client_config.default.access_token
  }
}

resource "local_file" "kubeconfig_out" {
  content  = "${data.template_file.kubeconfig.rendered}"
  filename = "${path.module}/kube_config"
}



