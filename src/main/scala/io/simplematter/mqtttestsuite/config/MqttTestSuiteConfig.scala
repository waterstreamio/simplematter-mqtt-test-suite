package io.simplematter.mqtttestsuite.config

import com.typesafe.config.{ConfigException, ConfigFactory}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.{SaslConfigs, SslConfigs}
import zio.ZIO

import scala.util.Random
import zio.config.*
import zio.json.*
import zio.config.typesafe.*
import zio.config.magnolia.*
//import zio.config.magnolia.DeriveConfigDescriptor._
import zio.duration.Duration
import io.simplematter.mqtttestsuite.util.singleParamMap
import io.simplematter.mqtttestsuite.model.NodeId
import io.simplematter.mqtttestsuite.exception.TestSuiteInitializationException

case class StatsConfig(statsPrintingIntervalMillis: Long = 5000,
                       statsUploadIntervalMillis: Long = 1000,
                       individualMessageTracking: Boolean = true,
                       errorsDetectionLoopIntervalMillis: Long = 10000,
                       considerMessageMissingTimeoutMillis: Long = 30000,
                       maxErrors: Int = 1000,
                       statsPort: Option[String],
                       printIssuesReport: Boolean = false) {
  val statsInterval: Duration = Duration.fromMillis(statsPrintingIntervalMillis)

  val statsUploadInterval: Duration = Duration.fromMillis(statsUploadIntervalMillis)

  val statsPortIntOption: Option[Int] = statsPort.filterNot(_.isBlank).map(_.trim.toInt)

}

case class HazelcastConfig(port: Option[Int] = None,
                           seedMembers: Option[String] = None,
                           k8sNamespace: Option[String] = None,
                           k8sPodLabelName: Option[String] = None,
                           k8sPodLabelValue: Option[String] = None,
                           k8sApiRetries: Int = 5,
//                           minNodesToStart: Option[Int] = None,
                           minNodesMaxWaitSeconds: Int = 300) {

  val tcpConfigEnabled: Boolean = seedMembers.filterNot(_.isBlank).isDefined

  val k8sConfigEnabled: Boolean = k8sPodLabelName.filterNot(_.isBlank).zip(k8sPodLabelValue.filterNot(_.isBlank)).isDefined

//  val seedMembersList: Seq[String] = seedMembers.fold(Nil)(_.split(",").map(_.trim))
}

case class MqttBrokerConfig(server: String,
                            connectionTimeoutSeconds: Int = 60,
                            subscribeTimeoutSeconds: Int = 600,
                            keepAliveSeconds: Int = 180,
                            autoKeepAlive: Boolean = false,
                            maxRetransmissionAttempts: Int = 3,
                            statusCheckIntervalSeconds: Int = 10,
                            persistentSession: Boolean = false,
                            messageMaxSize: Int = 1000000
                           ) {
  val serverHost: String = server.split(":", 2).head

  val serverPort: Int = server.split(":", 2).drop(1).headOption.map(_.toInt).getOrElse(1883)
}

object MqttBrokerConfig {
  implicit val encoder: JsonEncoder[MqttBrokerConfig] = DeriveJsonEncoder.gen[MqttBrokerConfig]
  implicit val decoder: JsonDecoder[MqttBrokerConfig] = DeriveJsonDecoder.gen[MqttBrokerConfig]
}

case class KafkaConfig(bootstrapServers: String,
                       sslEndpointIdentificationAlgorithm: Option[String] = None,
                       saslJaasConfig: Option[String] = None,
                       saslMechanism: Option[String] = None,
                       securityProtocol: String = "PLAINTEXT",
                       lingerMs: Int = 100,
                       batchSize: Int = 65392,
                       compressionType: String = "snappy",
                       requestTimeoutMs: Int = 30000,
                       retryBackoffMs: Long = 100L,
                       maxBlockMs: Int = 60000,
                       bufferMemory: Int = 33554432,
                       fetchMinBytes: Int = 1,
                       fetchMaxBytes: Int = 52428800,
                       fetchMaxWaitMs: Int = 500,
                       producerAcks: String = "all",
                       pollTimeoutMs: Long = 50
                      ) {
  lazy val bootstrapServersSeq: Seq[String] = bootstrapServers.split(",").map(_.trim)

  private lazy val commonProperties: Map[String, AnyRef] =
    singleParamMap(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, sslEndpointIdentificationAlgorithm) ++
      singleParamMap(SaslConfigs.SASL_JAAS_CONFIG, saslJaasConfig) ++
      singleParamMap(SaslConfigs.SASL_MECHANISM, saslMechanism) ++
      Map(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG -> securityProtocol,
        CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG -> int2Integer(requestTimeoutMs),
        CommonClientConfigs.RETRY_BACKOFF_MS_CONFIG -> long2Long(retryBackoffMs))

  lazy val consumerProperties: Map[String, AnyRef] =
    commonProperties ++
      Map(ConsumerConfig.FETCH_MIN_BYTES_CONFIG -> int2Integer(fetchMinBytes),
        ConsumerConfig.FETCH_MAX_BYTES_CONFIG -> int2Integer(fetchMaxBytes),
        ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG -> int2Integer(fetchMaxWaitMs))

  lazy val producerProperties: Map[String, AnyRef] =
    commonProperties ++
      Map(ProducerConfig.ACKS_CONFIG -> producerAcks)


  lazy val adminProperties: Map[String, AnyRef] = commonProperties

  lazy val pollTimeout: Duration = Duration.fromMillis(pollTimeoutMs)
}

case class ErrorInjectionConfig(publishDuplicatePercentage: Int = 0,
                                publishMissedPercentage: Int = 0,
                                receiveDuplicatePercentage: Int = 0,
                                receiveMissedPercentage: Int = 0)

case class MqttTestSuiteConfig(stepIntervalMillis: Long,
                               completionTimeoutMillis: Long,
                               nodeId: Option[String] = None,
                               stats: StatsConfig,
                               hazelcast: HazelcastConfig,
                               mqtt: MqttBrokerConfig,
                               kafka: KafkaConfig,
                               scenarioConfigInline: Option[String] = None,
                               scenarioConfigPath: Option[String] = None,
                               expectedRunnerNodesCount: Int = 1,
                               expectedAggregatorNodesCount: Int = 0,
                               errorInjection: ErrorInjectionConfig = ErrorInjectionConfig()) {
  val stepInterval: Duration = Duration.fromMillis(stepIntervalMillis)
  val completionTimeout: Duration = Duration.fromMillis(completionTimeoutMillis)
  val nodeIdNonEmpty: NodeId = NodeId(nodeId.filter(_.nonEmpty).getOrElse("%04d".format(Random.nextInt(9999))))

  val expectedHazelcastNodesNumber = expectedRunnerNodesCount + expectedAggregatorNodesCount
}

object MqttTestSuiteConfig {

  //workaround for zio-config when there are many fields in the config classes:  no implicit argument of type zio.config.magnolia.Descriptor[ io.simplematter.mqtttestsuite.config².ScenariosConfig ] was found
//  given scenariosConfigDescription: zio.config.magnolia.Descriptor[ScenariosConfig] = zio.config.magnolia.Descriptor(ScenarioConfig.automaticDescription, None)

  private val automaticDescription = descriptor[MqttTestSuiteConfig]

  def load(): MqttTestSuiteConfig = {
    zio.Runtime.default.unsafeRun(read(automaticDescription from TypesafeConfigSource.fromResourcePath))
  }
}
