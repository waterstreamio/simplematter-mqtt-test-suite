Scripts for running simplematter-mqtt-test-suite on Kubernetes 
==============================================================

How to run on GCP GKE
---------------------

1. Deploy the MQTT broker with Kafka synchronization that you want to test (for example, use https://github.com/simplematter/waterstream-gcp-terraform to deploy Waterstream) 
2. Configure and run GKE cluster:
```
    cd gcp_gke_cluster
    terraform apply --auto-approve 
``` 
3. Configura and run the test suite. In another terminal:
```
    cd test_suite
    cp config.auto.tfvars.example config.auto.tfvars
    vim config.auto.tfvars #At the very least specify mqtt_server and kafka_bootstrap_servers 
    terraform apply --auto-approve
```
3. Open the aggregator link in web browser to see the progress and the results
4. Clean up the resources when done:
```
    terraform destrou --auto-approve
    cd ../gcp_gke_cluster
    terraform destroy --auto-approve
```

### Resources required

Individual message tracking in mqtt2kafka scenario is the most resource-consuming.
To have 1k clients with 1 msg/sec for each per node you'll need at least `node_cpu="4"`, `node_memory="4096M"`.

### Misc commands

Get GCP GKE credentials:

    gcloud container clusters get-credentials CLUSTER_NAME \
    --region REGION \
    --project=PROJECT_ID

List namespaces:

    kubectl get namespace


### Permissions required

- `container.clusterRoles.create`
- `container.clusterRoleBindings.create`
