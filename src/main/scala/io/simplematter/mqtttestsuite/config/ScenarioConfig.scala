package io.simplematter.mqtttestsuite.config

import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions}
import io.simplematter.mqtttestsuite.config.MqttTestSuiteConfig.automaticDescription
import io.simplematter.mqtttestsuite.exception.TestSuiteInitializationException
import zio.config.typesafe.TypesafeConfigSource
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}
import zio.ZIO
import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*

import java.io.File

sealed trait ScenarioConfig {
  val scenarioName: String
  val topicPrefix: String
  val randomizeClientPrefix: Boolean
  val messageMinSize: Int
  val messageMaxSize: Int
  val topicsPerNode: Int
  val topicGroupsPerNode: Int

  /**
   * Are messages of the node isolated from the messages of the other node
   */
  val nodeMessagesIsolated: Boolean = true

  val guaranteesNoLost: Boolean

  val guaranteesNoDuplicates: Boolean
}

object ScenarioConfig {
  implicit val encoder: JsonEncoder[ScenarioConfig] = DeriveJsonEncoder.gen[ScenarioConfig]
  implicit val decoder: JsonDecoder[ScenarioConfig] = DeriveJsonDecoder.gen[ScenarioConfig]

  private def inlineTypesafeConfig(testSuiteConfig: MqttTestSuiteConfig): Option[Config] =
    testSuiteConfig.scenarioConfigInline.filter(_.nonEmpty).map { configInline =>
      ConfigFactory.parseString(configInline)
    }

  private def typesafeConfigFromFile(testSuiteConfig: MqttTestSuiteConfig): Option[Config] =
    testSuiteConfig.scenarioConfigPath.filter(_.nonEmpty).map { configFileName =>
      ConfigFactory.parseFile(File(configFileName), ConfigParseOptions.defaults().setAllowMissing(false))
    }

  private def qosNoLost(qos: Int): Boolean = qos == 1 || qos == 2

  private def qosNoDuplicates(qos: Int): Boolean = qos == 0 || qos == 2

  def load(testSuiteConfig: MqttTestSuiteConfig): ScenarioConfig = {
    val typesafeConfig = inlineTypesafeConfig(testSuiteConfig).
      orElse(typesafeConfigFromFile(testSuiteConfig)).
      getOrElse(throw new TestSuiteInitializationException("Scenario config not provided")).resolve()

    val scenarioName = typesafeConfig.getString("scenarioName")
    val cfgSource = TypesafeConfigSource.fromTypesafeConfig(ZIO.succeed(typesafeConfig))

    zio.Unsafe.unsafe {
      zio.Runtime.default.unsafe.run(scenarioName match {
        case ScenarioConfig.MqttPublishOnly.name => read(MqttPublishOnly.automaticDescription from cfgSource)
        case ScenarioConfig.MqttToKafka.name => read(MqttToKafka.automaticDescription from cfgSource)
        case ScenarioConfig.KafkaToMqtt.name => read(KafkaToMqtt.automaticDescription from cfgSource)
        case ScenarioConfig.MqttToMqtt.name => read(MqttToMqtt.automaticDescription from cfgSource)
        case other => throw new TestSuiteInitializationException(s"Unknown scenario: ${other}")
      }).getOrThrowFiberFailure()
    }
  }

  //workaround for zio-config when there are many fields in the config classes:  no implicit argument of type zio.config.magnolia.Descriptor[ io.simplematter.mqtttestsuite.config².ScenariosConfig ] was found

//  private[config] val automaticDescription: zio.config.ConfigDescriptor[ScenariosConfig] = descriptor[ScenariosConfig]



  case class MqttPublishOnly(
                              rampUpSeconds: Int = 10,
                              actionsDuringRampUp: Boolean = true,
                              durationSeconds: Int = 30,
                              topicsPerNode: Int = 100,
                              topicGroupsPerNode: Int = 10,
                              publishingClientsNumber: Int = 10,
                              clientMessagesPerSecond: Double = 1,
                              topicPrefix: String = "mqtt-load-sim",
                              clientPrefix: String = "mqtt-load-simulator",
                              randomizeClientPrefix: Boolean = true,
                              qos: Int = 0,
                              messageMinSize: Int = 800,
                              messageMaxSize: Int = 1200) extends ScenarioConfig {
    override val scenarioName: String = MqttPublishOnly.name

    override val guaranteesNoLost: Boolean = false //not applicable

    override val guaranteesNoDuplicates: Boolean = false //not applicable
  }

  object MqttPublishOnly {
    val name: String = "mqttPublishOnly"
    private[config] val automaticDescription = descriptor[MqttPublishOnly]
  }

  case class MqttToMqtt(
                         rampUpSeconds: Int = 10,
                         actionsDuringRampUp: Boolean = false,
                         durationSeconds: Int = 30,
                         topicsPerNode: Int = 100,
                         topicGroupsPerNode: Int = 10,
                         publishingClientsPerNode: Int = 10,
                         publishingClientMessagesPerSecond: Double = 1,
                         publishingClientPrefix: String = "mqtt-load-simulator-pub",
                         publishQos: Int = 0,
                         publishConnectionMonkey: ConnectionMonkeyConfig = ConnectionMonkeyConfig.disabled,
                         subscribingClientsPerNode: Int = 10,
                         subscribingClientPrefix: String = "mqtt-load-simulator-sub",
                         subscribeQos: Int = 0,
                         subscribeConnectionMonkey: ConnectionMonkeyConfig = ConnectionMonkeyConfig.disabled,
                         subscribeTopicsPerClient: Int = 1,
                         subscribeTopicGroupsPerClient: Int = 0,
                         subscribeWildcardMessageDeduplicate: Boolean = true,
                         topicPrefix: String = "mqtt-load-sim",
                         randomizeClientPrefix: Boolean = true,
                         messageMinSize: Int = 800,
                         messageMaxSize: Int = 1200) extends ScenarioConfig {
    override val scenarioName: String = MqttToMqtt.name

    override val guaranteesNoLost: Boolean = qosNoLost(publishQos) && qosNoLost(subscribeQos)

    override val guaranteesNoDuplicates: Boolean = qosNoDuplicates(publishQos) && qosNoDuplicates(subscribeQos)
  }

  object MqttToMqtt {
    val name: String = "mqttToMqtt"
    private[config] val automaticDescription = descriptor[MqttToMqtt]
  }

  case class MqttToKafka(
                          rampUpSeconds: Int = 10,
                          actionsDuringRampUp: Boolean = true,
                          durationSeconds: Int = 30,
                          topicsPerNode: Int = 100,
                          topicGroupsPerNode: Int = 10,
                          publishingClientsPerNode: Int = 10,
                          clientMessagesPerSecond: Double = 1,
                          topicPrefix: String = "mqtt-load-sim",
                          clientPrefix: String = "mqtt-load-simulator",
                          randomizeClientPrefix: Boolean = true,
                          qos: Int = 0,
                          messageMinSize: Int = 800,
                          messageMaxSize: Int = 1200,
                          kafkaGroupId: String = "mqtt_test",
                          kafkaTopics: String = "mqtt_messages",
                          useKafkaTimestampForLatency: Boolean = false,
                          connectionMonkey: ConnectionMonkeyConfig = ConnectionMonkeyConfig.disabled) extends ScenarioConfig {

    override val scenarioName: String = MqttToKafka.name

    override val nodeMessagesIsolated: Boolean = false

    override val guaranteesNoLost: Boolean = qosNoLost(qos)

    override val guaranteesNoDuplicates: Boolean = qosNoDuplicates(qos)

    lazy val kafkaTopicsSeq: Seq[String] = kafkaTopics.split(",").map(_.trim)
  }

  object MqttToKafka {
    val name: String = "mqttToKafka"
    private[config] val automaticDescription = descriptor[MqttToKafka]
  }

  case class KafkaToMqtt(
                          rampUpSeconds: Int = 10,
                          actionsDuringRampUp: Boolean = true,
                          durationSeconds: Int = 30,
                          topicsPerNode: Int = 100,
                          topicGroupsPerNode: Int = 10,
                          subscribingClientsPerNode: Int = 10,
                          subscribeTopicsPerClient: Int = 1,
                          subscribeTopicGroupsPerClient: Int = 0,
                          subscribeWildcardMessageDeduplicate: Boolean = true,
                          topicPrefix: String = "mqtt-load-sim",
                          clientPrefix: String = "mqtt-load-simulator",
                          randomizeClientPrefix: Boolean = true,
                          qos: Int = 0,
                          kafkaProducerMessagesPerSecond: Double = 1,
                          kafkaProducerAcks: String = "1",
                          messageMinSize: Int = 800,
                          messageMaxSize: Int = 1200,
                          kafkaDefaultTopic: String = "mqtt_messages",
                          connectionMonkey: ConnectionMonkeyConfig = ConnectionMonkeyConfig()) extends ScenarioConfig {
    override val scenarioName: String = KafkaToMqtt.name

    override val guaranteesNoLost: Boolean = qosNoLost(qos)

    override val guaranteesNoDuplicates: Boolean = qosNoDuplicates(qos)

  }

  object KafkaToMqtt {
    val name: String = "kafkaToMqtt"
    private[config] val automaticDescription = descriptor[KafkaToMqtt]
  }
}

