package io.simplematter.mqtttestsuite.hazelcast

import com.hazelcast.core.{Hazelcast, HazelcastInstance}
import com.hazelcast.kubernetes.KubernetesProperties
import io.simplematter.mqtttestsuite.config.HazelcastConfig
import io.simplematter.mqtttestsuite.model.{NodeId, NodeIndex}
import org.slf4j.LoggerFactory
import zio.{RIO, Task, TaskLayer, ZIO, ZLayer}
import zio.Clock
import java.util.concurrent.TimeoutException
import zio.Duration
import zio.Schedule
import scala.jdk.CollectionConverters.*

object HazelcastUtil {
  private val log = LoggerFactory.getLogger(HazelcastUtil.getClass)

  def hazelcastInstanceLayer(hazelcastConfig: HazelcastConfig): TaskLayer[HazelcastInstance] = {
    ZLayer.scoped {
      ZIO.acquireRelease(ZIO.attempt { createHazelcastInstance(hazelcastConfig) })({
        (hzInstance: HazelcastInstance) =>
          ZIO.attempt ({
            log.debug("Shutting down Hazelcast instance")
            hzInstance.shutdown()
          }).catchAll(e => ZIO.succeed {
            log.error("Failed to shut down Hazelcast instance", e)
          })
      })
    }
//    ZLayer.fromAcquireRelease[Any, Throwable, HazelcastInstance](ZIO.attempt {
//      createHazelcastInstance(hazelcastConfig)
//    })({ (hzInstance: HazelcastInstance) =>
//      ZIO({
//        log.debug("Shutting down Hazelcast instance")
//        hzInstance.shutdown()
//      }).catchAll(e => ZIO.succeed {
//        log.error("Failed to shut down Hazelcast instance", e)
//      })
//    })
  }

  private def createHazelcastInstance(hazelcastConfig: HazelcastConfig): HazelcastInstance = {
    val hzConfig = new com.hazelcast.config.Config()

    hazelcastConfig.port.foreach { port =>
      hzConfig.getNetworkConfig.setPort(port)
    }

    val joinCfg = hzConfig.getNetworkConfig.getJoin
    joinCfg.getAutoDetectionConfig.setEnabled(false)
    joinCfg.getMulticastConfig.setEnabled(false)

    if (hazelcastConfig.tcpConfigEnabled) {
      log.debug(s"TCP/IP join config enabled. Seed members: ${hazelcastConfig.seedMembers}")
      joinCfg.getTcpIpConfig.setEnabled(true)
      hazelcastConfig.seedMembers.filterNot(_.isBlank).foreach { members =>
        joinCfg.getTcpIpConfig.addMember(members)
      }
    }

    if (hazelcastConfig.k8sConfigEnabled) {
      log.debug(s"K8s config enabled. Namespace: ${hazelcastConfig.k8sNamespace}, Pod label name: ${hazelcastConfig.k8sPodLabelName}, Pos label value: ${hazelcastConfig.k8sPodLabelValue}")
      val cfg = joinCfg.getKubernetesConfig
      cfg.setEnabled(true)
      hazelcastConfig.k8sNamespace.foreach(cfg.setProperty(KubernetesProperties.NAMESPACE.key(), _))
      hazelcastConfig.k8sPodLabelName.filterNot(_.isBlank).foreach(cfg.setProperty(KubernetesProperties.POD_LABEL_NAME.key(), _))
      hazelcastConfig.k8sPodLabelValue.filterNot(_.isBlank).foreach(cfg.setProperty(KubernetesProperties.POD_LABEL_VALUE.key(), _))
      cfg.setProperty(KubernetesProperties.KUBERNETES_API_RETIRES.key(), hazelcastConfig.k8sApiRetries.toString)
    }

    val hzInst = Hazelcast.newHazelcastInstance(hzConfig)

    log.info(s"Hazelcast instance started: name=${hzInst.getName}, cluster=${hzInst.getCluster}")

//    hzInst.getCluster.getMembers.size()

    hzInst
  }

  def waitForMinSize(minSize: Option[Int], maxWaitSeconds: Int): RIO[Clock with HazelcastInstance, Unit] = {
    ZIO.service[HazelcastInstance].map { hzInst =>
      minSize.fold {
        log.debug("No minimal size requirement for Hazelcast cluster - proceeding")
        true
      } { s =>
        val actualSize = hzInst.getCluster.getMembers.size()
        if (actualSize >= s) {
          log.debug(s"Minimal Hazelcast cluster size: {}, actual: {} - proceeding", s, actualSize)
          true
        } else {
          log.debug(s"Minimal Hazelcast cluster size: {}, actual: {} - waiting", s, actualSize)
          false
        }
      }
    }.repeat(Schedule.recurUntilEquals(true) && Schedule.spaced(Duration.fromSeconds(10)))
      .timeoutFail(throw new TimeoutException(s"Timed out waiting for Hazelcast cluser minimal size"))(Duration.fromSeconds(maxWaitSeconds))
      .as(())
  }

  /**
   * 
   * @param expectedNodesCount
   * @param nodeId
   * @param maxWaitSeconds
   * @return 
   */
  def getNodeIndex(expectedNodesCount: Int, nodeId: NodeId, maxWaitSeconds: Int): RIO[Clock with HazelcastInstance, NodeIndex] = {
    for {
      hz <- ZIO.service[HazelcastInstance]
      idsSet = hz.getSet[NodeId](nodeIdSetName)
      _ = idsSet.add(nodeId)
      _ <- ZIO.attempt { idsSet.size() }
        .repeat(Schedule.recurUntil[Int](_ >= expectedNodesCount) && Schedule.spaced(Duration.fromSeconds(2)))
        .timeoutFail(throw new TimeoutException(s"Timed out waiting for the expected nodes count"))(Duration.fromSeconds(maxWaitSeconds))
        .as(())
      allIds = idsSet.asScala.toSeq.sortBy(_.value)
      idx = allIds.indexOf(nodeId)
      _ = log.debug("This node {} index is {} out of {} (expected {}): {}", nodeId, idx, allIds.size, expectedNodesCount, allIds)
      _ <- if(idx >= 0) ZIO.succeed(()) else ZIO.fail(new IllegalStateException(s"This node ID ${nodeId} not found in all node IDs seq ${allIds}"))
    } yield NodeIndex(idx, expectedNodesCount)
  }


  private val nodeIdSetName = "mqtt_test_node_ids"
}
