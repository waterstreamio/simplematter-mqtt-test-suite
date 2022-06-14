variable "namespace" {
  type = string
  default = "mqtt-test"
}

variable "mqtt_test_suite_version" {
  type = string
  default = "0.0.4"
}

###############################################################
## Docker
###############################################################

variable "docker_server" {
  type = string
  default = "index.docker.io"
}

variable "docker_username" {
  type = string
}

variable "docker_password" {
  type = string
}

###############################################################
## Kubernetes
###############################################################

variable "kubernetes_config_path" {
  type = string
  default = "../kube_config"
}

###############################################################
## MQTT
###############################################################

variable "mqtt_server" {
  type = string
}

variable "mqtt_connection_timeout_seconds" {
  type = number
  default = 60
}

variable "mqtt_subscribe_timeout_seconds" {
  type = number
  default = 600
}

variable "mqtt_keep_alive_seconds" {
  type = number
  default = 180
}

variable "mqtt_max_retransmission_attempts" {
  type = number
  default = 3
}

variable "mqtt_status_check_interval_seconds" {
  type = number
  default = 10
}

variable "mqtt_persistent_session" {
  type = bool
  default = false
}

variable "mqtt_message_max_size_bytes" {
  type = number
  default = 1000000
}

###############################################################
## Kafka
###############################################################

variable "kafka_bootstrap_servers" {
  type = string
  default = ""
}

variable "kafka_sslEndpointIdentificationAlgorithm" {
  type = string
  description = "ssl.endpoint.identification.algorithm for producer and consumer"
  default = ""
}

variable "kafka_saslJaasConfig" {
  description = "sasl.jaas.config for producer and consumer"
  type = string
  default = ""
}

variable "kafka_saslMechanism" {
  type = string
  description = "`sasl.mechanism` for producer and consumer"
  default = ""
}

variable "kafka_securityProtocol" {
  type = string
  description = "security.protocol for producer and consumer"
  default = "PLAINTEXT"
}

variable "kafka_lingerMs" {
  type = number
  default = 100
}

variable "kafka_batchSize" {
  type = number
  default = 65392
}

variable "kafka_compressionType" {
  type = string
  description = "compression.type for producer. Valid values are none, gzip, snappy, lz4"
  default = "snappy"
}

variable "kafka_requestTimeoutMs" {
  type = number
  description = "request.timeout.ms for producer and consumer"
  default = 30000
}

variable "kafka_retryBackoffMs" {
  type = number
  description = "retry.backoff.ms for producer and consumer"
  default = 100
}

variable "kafka_maxBlockMs" {
  type = number
  description = "`max.block.ms` for producer"
  default = 60000
}

variable "kafka_bufferMemory" {
  type = number
  description = "`buffer.memory` for producer. Default is 32MB"
  default = 33554432
}

variable "kafka_fetchMinBytes" {
  type = number
  description = "`fetch.min.bytes` for consumer. Default is 1"
  default = 1
}

variable "kafka_fetchMaxBytes" {
  type = number
  description = "`fetch.max.bytes` for consumer. Default is 50 MB"
  default = 52428800
}

variable "kafka_fetchMaxWaitMs" {
  type = number
  description = "`fetch.max.wait.ms` for consumer. Default is 500"
  default = 500
}

variable "kafka_producerAcks" {
  type = string
  description = "`acks` for producer. Valid values are `all`, `-1`, `0`, `1`. `all` and `-1` are equivalent"
  default = "all"
}

variable "kafka_default_topic" {
  default = "mqtt_messages"
  type = string
}


###############################################################
## Scenario
###############################################################

variable "node_count" {
  default = 1
  type = number
}

variable "node_cpu" {
  default = "2"
  type = string
}

variable "node_memory" {
  default = "2048M"
  type = string
}

variable "node_ephemeral_storage" {
  default = "10Gi"
  type = string
}

variable "node_heap_percentage" {
  default = "70"
  type = string
}

variable "test_scenario_file" {
  type = string
}


variable "stats_individual_message_tracking" {
  type = bool
  default = true
}

variable "error_detection_loop_interval_millis" {
  type = number
  default = 10000
}

variable "consider_message_missing_timeout_millis" {
  type = number
  default = 30000
}

variable "stats_print_runner_issues_report" {
  type = bool
  default = false
}

variable "completion_timeout_millis" {
  type = number
  default = 10000
}

variable "runner_additional_java_opts" {
  type = string
  default = ""
}

variable "aggregator_additional_java_opts" {
  type = string
  default = ""
}

#variable "stats_kafka_timestamp_for_latency" {
#  type = bool
#  default = false
#}