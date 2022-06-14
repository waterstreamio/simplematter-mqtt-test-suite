provider "kubernetes" {
  config_path    = var.kubernetes_config_path
}

# Use a random suffix to prevent overlap in names
resource "random_string" "test_suffix" {
  length = 6
  special = false
  upper = false
}

resource "kubernetes_namespace" "mqtt-test" {
  metadata {
    name = var.namespace
  }
}

resource "kubernetes_cluster_role" "hazelcast-cluster-role" {
  metadata {
    name = "hazelcast-cluster-role"
  }

  rule {
    api_groups = [""]
    resources  = ["endpoints", "pods", "nodes", "services"]
    verbs      = ["get", "list"]
  }
}

resource "kubernetes_cluster_role_binding" "hazelcast-cluster-role-binding" {
  metadata {
    name = "hazelcast-cluster-role-binding"
  }

  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "ClusterRole"
    name      = "hazelcast-cluster-role"
  }

  subject {
    kind = "ServiceAccount"
    name = "default"
    namespace = kubernetes_namespace.mqtt-test.metadata.0.name
  }
}

resource "kubernetes_secret" "dockerhub-download" {
  metadata {
    name = "dockerhub-download"
    namespace = kubernetes_namespace.mqtt-test.metadata.0.name
  }

  data = {
    ".dockerconfigjson" = jsonencode({
      auths = {
        (var.docker_server) = {
#          username = var.docker_username
#          password = var.docker_password
          auth = base64encode("${var.docker_username}:${var.docker_password}")
        }
      }
    })
  }

  type = "kubernetes.io/dockerconfigjson"
}

resource "kubernetes_config_map" "etc_testsuite" {
  metadata {
    name = "etc-testsuite"
    namespace = kubernetes_namespace.mqtt-test.metadata.0.name
  }

  data = {
    logback_xml = file("${path.module}/resources/logback.xml")
  }
}



resource "kubernetes_pod" "mqtt-test-runner" {
  count = var.node_count

  metadata {
    name = "mqtt-test-runner-${count.index}"
    namespace = kubernetes_namespace.mqtt-test.metadata.0.name
    labels = {
      app = "mqtt-test-runner"
      hazelcast-cluster = "mqtt-test-runner"
    }
  }
  spec {
    image_pull_secrets {
      name = kubernetes_secret.dockerhub-download.metadata.0.name
    }

    hostname = "mqtt-test-aggregator-${count.index}"

    restart_policy = "Never"

    container {
      name = "runner"
      image = "simplematter/simplematter-mqtt-test-suite:${var.mqtt_test_suite_version}"

      image_pull_policy = "Always"
      resources {
        requests = {
          cpu = var.node_cpu
          memory = var.node_memory
        }
        limits = {
          ephemeral-storage = var.node_ephemeral_storage
        }
      }
      port {
        container_port = 5701
        name = "hazelcast"
      }
      env {
        name = "MQTT_TEST_SUITE_JAVA_OPTS"
        value = "-XX:InitialRAMPercentage=${var.node_heap_percentage} -XX:MaxRAMPercentage=${var.node_heap_percentage} ${var.runner_additional_java_opts}"
      }
      env {
        name = "MQTT_LOAD_STATS_UPLOADING_INTERVAL_MILLIS"
        value = "10000"
      }
      env {
        name = "MQTT_LOAD_STATS_PRINTING_INTERVAL_MILLIS"
        value = "30000"
      }
      env {
        name = "MQTT_LOAD_STATS_INDIVIDUAL_MESSAGE_TRACKING"
        value = tostring(var.stats_individual_message_tracking)
      }
      env {
        name = "MQTT_LOAD_STATS_ERRORS_DETECTION_LOOP_INTERVAL_MILLIS"
        value = tostring(var.error_detection_loop_interval_millis)
      }
      env {
        name = "MQTT_LOAD_STATS_CONSIDER_MESSAGE_MISSING_TIMEOUT_MILLIS"
        value = tostring(var.consider_message_missing_timeout_millis)
      }
      env {
        name = "MQTT_LOAD_STATS_PRINT_ISSUES_REPORT"
        value = tostring(var.stats_print_runner_issues_report)
      }
      env {
        name = "MQTT_LOAD_HAZELCAST_PORT"
        value = "5701"
      }
      env {
        name = "MQTT_TEST_SUITE_AGGREGATOR"
        value = "0"
      }
      env {
        name = "MQTT_LOAD_SERVER"
        value = var.mqtt_server
      }
      env {
        name = "MQTT_LOAD_CONNECTION_TIMEOUT_SECONDS"
        value = tostring(var.mqtt_connection_timeout_seconds)
      }
      env {
        name = "MQTT_LOAD_SUBSCRIBE_TIMEOUT_SECONDS"
        value = tostring(var.mqtt_subscribe_timeout_seconds)
      }
      env {
        name = "MQTT_LOAD_KEEP_ALIVE_SECONDS"
        value = tostring(var.mqtt_keep_alive_seconds)
      }
      env {
        name = "MQTT_LOAD_MAX_RETRANSMISSION_ATTEMPTS"
        value = tostring(var.mqtt_max_retransmission_attempts)
      }
      env {
        name = "MQTT_LOAD_STATUS_CHECK_INTERVAL_SECONDS"
        value = tostring(var.mqtt_status_check_interval_seconds)
      }
      env {
        name = "MQTT_LOAD_PERSISTENT_SESSION"
        value = tostring(var.mqtt_persistent_session)
      }
      env {
        name = "MQTT_LOAD_MESSAGE_MAX_SIZE"
        value = tostring(var.mqtt_message_max_size_bytes)
      }
      env {
        name = "MQTT_LOAD_KAFKA_BOOTSTRAP_SERVERS"
        value = var.kafka_bootstrap_servers
      }
      env {
        name = "MQTT_LOAD_KAFKA_DEFAULT_TOPIC"
        value = var.kafka_default_topic
      }
      env {
        name  = "MQTT_LOAD_SCENARIO_CONFIG_INLINE"
        value = file(var.test_scenario_file)
      }
      env {
        name = "MQTT_LOAD_HAZELCAST_K8S_NAMESPACE"
        value = kubernetes_namespace.mqtt-test.metadata.0.name
      }
      env {
        name = "MQTT_LOAD_HAZELCAST_K8S_POD_LABEL_NAME"
        value = "hazelcast-cluster"
      }
      env {
        name = "MQTT_LOAD_HAZELCAST_K8S_POD_LABEL_VALUE"
        value = "mqtt-test-runner"
      }
      env {
        name = "MQTT_LOAD_EXPECTED_RUNNER_NODES_COUNT"
        value = tostring(var.node_count)
      }
      env {
        name = "MQTT_LOAD_EXPECTED_AGGREGATOR_NODES_COUNT"
        value = 1
      }
      env {
        name = "MQTT_LOAD_HAZELCAST_MIN_NODES_MAX_WAIT_SECONDS"
        value = "300"
      }
      env {
        name = "MQTT_LOAD_COMPLETION_TIMEOUT_MILLIS"
        value = var.completion_timeout_millis
      }
      env {
        name = "KAFKA_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM"
        value = var.kafka_sslEndpointIdentificationAlgorithm
      }
      env {
        name = "KAFKA_SASL_JAAS_CONFIG"
        value = var.kafka_saslJaasConfig
      }
      env {
        name = "KAFKA_SASL_MECHANISM"
        value = var.kafka_saslMechanism
      }
      env {
        name = "KAFKA_SECURITY_PROTOCOL"
        value = var.kafka_securityProtocol
      }
      env {
        name = "KAFKA_PRODUCER_LINGER_MS"
        value = tostring(var.kafka_lingerMs)
      }
      env {
        name = "KAFKA_BATCH_SIZE"
        value = tostring(var.kafka_batchSize)
      }
      env {
        name = "KAFKA_COMPRESSION_TYPE"
        value = var.kafka_compressionType
      }
      env {
        name = "KAFKA_REQUEST_TIMEOUT_MS"
        value = tostring(var.kafka_requestTimeoutMs)
      }
      env {
        name = "KAFKA_RETRY_BACKOFF_MS"
        value = tostring(var.kafka_retryBackoffMs)
      }
      env {
        name = "KAFKA_MAX_BLOCK_MS"
        value = tostring(var.kafka_maxBlockMs)
      }
      env {
        name = "KAFKA_BUFFER_MEMORY"
        value = tostring(var.kafka_bufferMemory)
      }
      env {
        name = "KAFKA_FETCH_MIN_BYTES"
        value = tostring(var.kafka_fetchMinBytes)
      }
      env {
        name = "KAFKA_FETCH_MAX_BYTES"
        value = tostring(var.kafka_fetchMaxBytes)
      }
      env {
        name = "KAFKA_FETCH_MAX_WAIT_MS"
        value = tostring(var.kafka_fetchMaxWaitMs)
      }
      env {
        name = "KAFKA_PRODUCER_ACKS"
        value = var.kafka_producerAcks
      }
      volume_mount {
        mount_path = "/etc/testsuite/"
        name       = "etc-testsuite"
      }
    }
    volume {
      name = "etc-testsuite"
      config_map {
        name = kubernetes_config_map.etc_testsuite.metadata.0.name
      }
    }
  }
}

resource "kubernetes_pod" "mqtt-test-aggregator" {
  metadata {
    name = "mqtt-test-aggregator"
    namespace = var.namespace
    labels = {
      app = "mqtt-test-aggregator"
      hazelcast-cluster = "mqtt-test-runner"
    }
  }
  spec {
    image_pull_secrets {
      name = kubernetes_secret.dockerhub-download.metadata.0.name
    }

    restart_policy = "Never"

    hostname = "mqtt-test-aggregator"

    container {
      name = "aggregator"
      image = "simplematter/simplematter-mqtt-test-suite:${var.mqtt_test_suite_version}"

      image_pull_policy = "Always"
      resources {
        requests = {
          cpu = var.node_cpu
          memory = var.node_memory
        }
      }
      port {
        container_port = 8080
        name = "stats-reporting"
      }
      port {
        container_port = 5701
#        container_port = 5801
        name = "hazelcast"
      }
      env {
        name = "MQTT_TEST_SUITE_JAVA_OPTS"
        value = "-XX:InitialRAMPercentage=${var.node_heap_percentage} -XX:MaxRAMPercentage=${var.node_heap_percentage} -Dlogback.configurationFile=/etc/testsuite/logback.xml ${var.runner_additional_java_opts}"
      }
      env {
        name = "MQTT_LOAD_STATS_PRINTING_INTERVAL_MILLIS"
        value = "30000"
      }
      env {
        name = "MQTT_LOAD_STATS_PORT"
        value = "8080"
      }
      env {
        name = "MQTT_LOAD_HAZELCAST_PORT"
        value = "5701"
      }
      env {
        name = "MQTT_TEST_SUITE_AGGREGATOR"
        value = "true"
      }
      env {
        name = "MQTT_LOAD_HAZELCAST_K8S_NAMESPACE"
        value = kubernetes_namespace.mqtt-test.metadata.0.name
      }
      env {
        name = "MQTT_LOAD_HAZELCAST_K8S_POD_LABEL_NAME"
        value = "hazelcast-cluster"
      }
      env {
        name = "MQTT_LOAD_HAZELCAST_K8S_POD_LABEL_VALUE"
        value = "mqtt-test-runner"
      }
    }
  }
}

data "kubernetes_pod" "mqtt-test-aggregator" {
  metadata {
    name      = kubernetes_pod.mqtt-test-aggregator.metadata.0.name
    namespace = var.namespace
  }
}

resource "kubernetes_service" "mqtt-test-aggregator-service" {
  metadata {
    name = "mqtt-test-aggregator-service"
    namespace = kubernetes_namespace.mqtt-test.metadata.0.name
  }
  spec {
    selector = {
      app = "mqtt-test-aggregator"
    }

    external_traffic_policy = "Local"
    type = "LoadBalancer"

    port {
      port = 8080
      target_port = "8080"
    }
  }
}

output "mqtt-test-aggregator-endpoint-url" {
  value = "http://${coalesce(kubernetes_service.mqtt-test-aggregator-service.status.0.load_balancer.0.ingress.0.hostname, kubernetes_service.mqtt-test-aggregator-service.status.0.load_balancer.0.ingress.0.ip)}:8080"
}



