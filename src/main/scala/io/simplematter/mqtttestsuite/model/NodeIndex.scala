package io.simplematter.mqtttestsuite.model

/**
 *
 * @param thisNode 0-bazed index of this node
 * @param totalNodes total nodes count
 */
case class NodeIndex(thisNode: Int, totalNodes: Int) {
  def pick[A](totalItems: Seq[A]): Seq[A] = {
    val itemsPerNode = if(totalNodes > 1) Math.ceil(totalItems.size.toDouble / totalNodes).toInt else totalItems.size
    val start = thisNode * itemsPerNode
    totalItems.slice(start, Math.min(start + itemsPerNode, totalItems.size))
  }
}
