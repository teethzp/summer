package engine
package tendermint

import tendermint.Stage.{Height, Round, ConsensusStep}
import data.BlockId
import scala.collection.immutable
import scala.collection.mutable

class VoteSet {
  /// we promise all signed message here are valid
  var votes: immutable.HashMap[ConsensusStep, mutable.HashMap[Address, SignedConsensusMessage]] = immutable.HashMap.empty

  def clear(): Unit = votes = immutable.HashMap.empty
  def insert(step: ConsensusStep, vote: SignedConsensusMessage): Unit = {
    /// val address = vote.from
    votes.find {
      case (s, _) => step == s
    } match {
      case Some((_, map)) => map += (vote.consensusMessage.from -> vote)
      case None => votes += (step -> mutable.HashMap(vote.consensusMessage.from -> vote))
    }
  }
  def insert(vote: SignedConsensusMessage): Unit = {
    insert(vote.consensusMessage.step, vote)
  }
  def remove(height: Height): Unit = votes = votes.filter {
    case (k, _) => k._1 != height
  }
  def remove(height: Height, round: Round): Unit = votes = votes.filter {
    case (k, _) => k._1 != height && k._2 != round
  }

  def get(height: Height) = votes.filter {
    case (k, _) => k._1 == height
  }
  def get(height: Height, round: Round) = votes.filter {
    case (k, _) => k._1 == height && k._2 == round
  }
  def get(height: Height, round: Round, stage: Stage) = votes.get((height, round, stage))
  def afterView(height: Height, round: Round) = votes.filter {
    case (k, _) => k._1 == height && k._2 > round
  }

  def prevoteSetAfter(height: Height, round: Round) = {
    votes.filter {
      case (k, _) => k._1 == height && k._2 > round && k._3 == PrevoteStage
    }
  }
}

object VoteSet {
  def apply() = new VoteSet
}

/// Proof of Lock Change, if blockId is None, vote for nil
case class PoLC(height: Height, round: Round, blockId: Option[BlockId], votes: immutable.HashMap[Address, SignedConsensusMessage])
