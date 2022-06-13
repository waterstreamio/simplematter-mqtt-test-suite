AWS EKS Kubernetes cluster
==========================

Some permissions the user must have to create the cluster:

- eks:CreateCluster
- iam:CreateOpenIDConnectProvider
- iam:TagOpenIDConnectProvider 
- iam:GetOpenIDConnectProvider
- iam:DeleteOpenIDConnectProvider

Permissions needed to admin the cluster with AWS UI:

- eks:AccessKubernetesApi

List the config maps:

    kubectl --kubeconfig kube_config get cm --all-namespaces 

Get some ConfigMap content:

    kubectl --kubeconfig kube_config describe cm --namespace aws-observability aws-logging