package engine
package tendermint

import akka.serialization._
import akka.actor.ActorSystem
import data.{Message, Block, BlockId}
import engine.tendermint.Stage.{Height, Round, ConsensusStep, ConsensusView}
import util.crypto.{Sign, Signature, Hashable, PrivateKey, PublicKey}

sealed trait ConsensusMessage extends Message {
  def from: Address
  def step: ConsensusStep
  def view: ConsensusView = (step._1, step._2)
  def signedMessage(signature: Signature, publicKey: PublicKey): SignedConsensusMessage
}
case class Proposal(height: Height, round: Round, block: Block) extends ConsensusMessage {
  /// TODO: generate address
  def from = new Address(20)
  def step = (height, round, ProposalStage)
  def signedMessage(signature: Signature, publicKey: PublicKey): SignedProposal = SignedProposal(this, signature, publicKey)
}
case class Prevote(height: Height, round: Round, id: Option[BlockId]) extends ConsensusMessage {
  def from = new Address(20)
  def step = (height, round, PrevoteStage)
  def signedMessage(signature: Signature, publicKey: PublicKey): SignedPrevote = SignedPrevote(this, signature, publicKey)
}
case class Precommit(height: Height, round: Round, id: Option[BlockId]) extends ConsensusMessage {
  def from = new Address(20)
  def step = (height, round, PrecommitStage)
  def signedMessage(signature: Signature, publicKey: PublicKey): SignedConsensusMessage = SignedPrecommit(this, signature, publicKey)
}

/// NOTE: because type erasure of match, we use case class, maybe shapeless can solve that
sealed trait SignedConsensusMessage extends Message {
  /// TODO: verify should not depended on ActorSystem, find a better way
  def isValid(implicit system: ActorSystem): Boolean = SignedConsensusMessage.isValid(this)
  def publicKey: PublicKey
  def consensusMessage: ConsensusMessage
}
case class SignedProposal(proposal: Proposal, signature: Signature, publicKey: PublicKey) extends SignedConsensusMessage {
  def consensusMessage = proposal
}
case class SignedPrevote(prevote: Prevote, signature: Signature, publicKey: PublicKey) extends SignedConsensusMessage {
  def consensusMessage = prevote
}
case class SignedPrecommit(precommit: Precommit, signature: Signature, publicKey: PublicKey) extends SignedConsensusMessage {
  def consensusMessage = precommit
}

object ConsensusMessage {
  /// use isInstanceOf
  def isPrevoteMessage(msg: ConsensusMessage): Boolean = {
    msg match {
      case _: Prevote => true
      case _ => false
    }
  }

  def isPrecommitMessage(msg: ConsensusMessage): Boolean = {
    msg match {
      case _: Precommit => true
      case _ => false
    }
  }

  def serialize(message: ConsensusMessage)(implicit system: ActorSystem): Array[Byte] = {
    val serialization = SerializationExtension(system)
    val serializer = serialization.findSerializerFor(message)
    serializer.toBinary(message)
  }

  /// TODO: sign should not depended on ActorSystem, find a better way
  def sign(message: ConsensusMessage)(implicit system: ActorSystem, privateKey: PrivateKey, publicKey: PublicKey): SignedConsensusMessage = {
    val hash = Hashable.hash(serialize(message))
    val signature = Sign.sign(hash, privateKey)
    message.signedMessage(signature, publicKey)
  }
}

object SignedConsensusMessage {
  import ConsensusMessage._
  def isValid(message: SignedConsensusMessage)(implicit system: ActorSystem): Boolean = {
    val (msg, signature, publicKey) = message match {
      case SignedProposal(p, s, k) => (p, s, k)
      case SignedPrevote(p, s, k) => (p, s, k)
      case SignedPrecommit(p, s, k) => (p, s, k)
    }
    val hash = Hashable.hash(serialize(msg))
    Sign.verify(signature, hash, publicKey)
  }
}
