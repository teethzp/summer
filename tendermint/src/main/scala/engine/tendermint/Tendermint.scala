package engine
package tendermint

import akka.actor.{Props, Actor, ActorRef, ActorSystem, ActorSelection}
import akka.event.Logging
import data.{Message, Block}
import util.crypto.{KeyPair, PrivateKey, PublicKey}
import tendermint.Stage.{Height, Round}
import scala.collection.mutable
import scala.collection.immutable
import scala.collection.JavaConverters
import scala.math.Ordering.Implicits._
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import com.typesafe.config.ConfigFactory

sealed trait Stage
case object ProposalStage extends Stage
case object PrevoteStage extends Stage
case object PrecommitStage extends Stage
case object CommitStage extends Stage

object Stage {
  type Height = Int
  type Round = Int
  type ConsensusView = (Height, Round)
  type ConsensusStep = (Height, Round, Stage)
}

/// actors represent with path , which can find the real actor with actor selection
class Tendermint(keyPair: KeyPair, actors: Seq[String], peerPublicKeys: Seq[PublicKey]) extends Actor {
  import Stage.{ConsensusView, ConsensusStep}
  private var currentHeight: Height = 0
  private var currentRound: Round = 0
  private var currentStage: Stage = ProposalStage
  private var peerNums = 0
  private val peers = mutable.ListBuffer.empty[ActorRef]
  private val proposals: mutable.HashMap[ConsensusView, SignedProposal] = mutable.HashMap.empty
  private val voteSet = VoteSet()
  /// Proof of Lock Change
  private var polc: Option[PoLC] = None
  /// NOTE: lastLockRound is NOT the round of polc
  private var lastLockRound: Option[Round] = None
  private implicit val system: ActorSystem = context.system
  private implicit val privateKey: PrivateKey = keyPair._2
  private implicit val publicKey: PublicKey = keyPair._1
  private val address = getAddress(publicKey)
  private val peerAddress = peerPublicKeys.map(getAddress)
  import system.dispatcher
  import ConsensusMessage._
  import Tendermint._

  private val log = Logging(system, this)

  log.info(s"Tendermint Node: $self")

  init()
  toStage(ProposalStage)

  /// round-robin
  private [this] def selfIsProposer: Boolean = address == peerAddress((currentHeight + currentRound) % (peerNums + 1))
  private [this] def isAt(stage: Stage) = currentStage == stage
  private [this] def newBlock: Block = new Block
  private [this] def newProposal: Proposal = Proposal(currentHeight, currentRound, newBlock)
  private [this] def pub(message: Message): Unit = peers.foreach(_ ! message)

  private [this] def init(): Unit = {
    peerNums = actors.size
    val nodes = mutable.ListBuffer.empty[ActorSelection]
    actors.map(context.actorSelection).foreach(nodes += _)
    nodes.foreach(connectToPeer)
  }

  private [this] def connectToPeer(peer: ActorSelection): Unit = {
    peer.resolveOne(2.seconds) onComplete {
      case Success(ref) =>
        log.info(s"connect to $peer, actorRef is $ref")
        ref ! Ping
      case Failure(exception) =>
        log.info(s"connect to $peer failed: $exception, re-connecting")
        system.scheduler.scheduleOnce(2.seconds) {
          connectToPeer(peer)
        }
    }
  }

  private [this] def toStage(stage: Stage): Unit = {
    stage match {
      case ProposalStage =>
        log.info(s"stage: $currentStage")
        if (selfIsProposer) {
          val proposal = newProposal
          val signedProposal = sign(proposal)
          pub(signedProposal)
          processProposal(signedProposal.asInstanceOf[SignedProposal])
        } else {
          val timeout = ProposalTimeout(currentHeight, currentRound)
          val _ = system.scheduler.scheduleOnce(timeout.duration) {
            self ! timeout
          }
        }
      case PrevoteStage =>
        log.info(s"stage: $currentStage")
        doPrevote()
      case PrecommitStage =>
        log.info(s"stage: $currentStage")
        doPrecommit()
      case CommitStage =>
        log.info(s"stage: $currentStage")
        doCommit()
    }
  }

  private [this] def doPrevote(): Unit = {
    /// NOTE: add self vote to voteSet
    tryUnlock()
    val vote = if (polc.isDefined) {
      require(lastLockRound.isDefined, "Now we have PoLC, but lastLockRound is None")
      Prevote(currentHeight, currentRound, polc.get.blockId)
    } else {
      val proposal = proposals.get(currentView)
      val blockId = proposal map { p => p.consensusMessage.block.id }
      Prevote(currentHeight, currentRound, blockId)
    }
    val signedPrevote = sign(vote)
    pub(signedPrevote)
    processPrevote(signedPrevote.asInstanceOf[SignedPrevote])
    val timeout = PrevoteTimeout(currentHeight, currentRound)
    /// TODO: maybe it is unnecessary to set prevote timeout when we current at precommit stage
    val _ = system.scheduler.scheduleOnce(timeout.duration) {
      self ! timeout
    }
  }

  private [this] def doPrecommit(): Unit = {
    hasPolcAtView(currentHeight, currentRound) map { p =>
      if (p.blockId.isDefined) {
        /// re-lock to this, and precommit this
        polc = Some(p)
        /// NOTE: lastLockRound is not the round of polc
        lastLockRound = Some(currentRound)
      } else {
        polc = None
        lastLockRound = None
      }
      Precommit(currentHeight, currentRound, p.blockId)
    } match {
      case Some(vote) =>
        val signedPrecommit = sign(vote)
        pub(signedPrecommit)
        processPrecommit(signedPrecommit.asInstanceOf[SignedPrecommit])
      case None =>
        val vote = Precommit(currentHeight, currentRound, None)
        val signedPrecommit = sign(vote)
        pub(signedPrecommit)
        processPrecommit(signedPrecommit.asInstanceOf[SignedPrecommit])
    }
    ///TODO: maybe it is unnecessary to set precommit timeout when we current at commit stage, like above
    val timeout = PrecommitTimeout(currentHeight, currentRound)
    val _ = system.scheduler.scheduleOnce(timeout.duration) {
      self ! timeout
    }
  }

  private [this] def doCommit(): Unit = {
    log.info(s"commit block at height $currentHeight")
    ///TODO: save block
    /// clean current height votes, clean polc, TODO: clean other things(proposals)
    voteSet.remove(currentHeight)
    polc = None
    lastLockRound = None
    currentHeight += 1
    currentRound = 0
    currentStage = ProposalStage
    toStage(ProposalStage)
  }

  private [this] def currentView = (currentHeight, currentRound)
  /// TODO: Maybe we should return Option[PoLC]
  private [this] def newPolc: Option[(ConsensusStep, mutable.HashMap[Address, SignedConsensusMessage])] = {
    import ConsensusMessage.isPrevoteMessage
    val polcVotes = voteSet.prevoteSetAfter(currentHeight, currentRound)
    /// Actually, we could not promise all votes at PrevoteStage are Prevote, likewise the Precommit
    /// There is a solution: when we receive vote message, which not consist with stage, just ignore that
    require(polcVotes.forall {
      case (_, v) => v.forall(tuple => isPrevoteMessage(tuple._2.consensusMessage))
    }, "expect Prevote message, but found others")
    polcVotes.find {
      case (_, set) =>
        val voted = set.values.groupBy(_.consensusMessage).mapValues(_.size)
        voted.exists(elem => isSatisfy(elem._2))
    }
  }

  private [this] def hasPolcAtView(height: Height, round: Round): Option[PoLC] = {
    voteSet.get(height, round, PrevoteStage) flatMap { set =>
      val voted = set.values.groupBy(_.consensusMessage)
      voted.find(x => isSatisfy(x._2.size)) map {
        case (rawVote, signedVotes) =>
          val votes = signedVotes.map(vote => getAddress(vote.publicKey) -> vote).toSeq
          PoLC(
            height,
            round,
            rawVote.asInstanceOf[Prevote].id,
            immutable.HashMap(votes: _*))
      }
    }
  }

  private [this] def hasEnoughVote(stage: Stage): Boolean = {
    val votes = voteSet.get(currentHeight, currentRound, stage) //Option[mutable.HashMap[Address, ConsensusMessage]]
    votes match {
      case Some(map) =>
        val voted = map.values.groupBy(_.consensusMessage).mapValues(_.size)
        voted.exists(elem => isSatisfy(elem._2))
      case None => false
    }
  }

  private [this] def hasEnoughVoteForNil: Boolean = {
    val votes = voteSet.get(currentHeight, currentRound, PrecommitStage)
    votes match {
      case Some(map) =>
        val voted = map.values.groupBy(_.consensusMessage).mapValues(_.size)
        voted.exists(elem => {
          val message = elem._1.asInstanceOf[Precommit]
          message.id.isEmpty && isSatisfy(elem._2)
        })
      case None => false
    }
  }

  private [this] def hasEnoughAnyVoteAtStage(stage: Stage): Option[Round] = {
    val votes = voteSet.afterView(currentHeight, currentRound)
    votes.find {
      case (step, voted) => step._3 == stage && isSatisfy(voted.size)
    } map {
      case (step, _) => step._2
    }
  }

  private [this] def hasEnoughAnyVoteAtRound(round: Round, stage: Stage): Boolean = {
    val votes = voteSet.get(currentHeight, round)
    votes.exists {
      case (step, voted) => step._3 == stage && isSatisfy(voted.size)
    }
  }

  private [this] def isSatisfy(num: Int): Boolean = {
    val p23 = (peerNums + 1) * 2 / 3
    num > p23
  }

  private [this] def proposalIsValid(p: Proposal, address: Address): Boolean = {
    val (height, round) = p.view
    val count = height + round
    (p.view >= currentView) &&
      (address == peerAddress(count % (peerNums + 1)))
  }

  /// private [this] def processProposal(p: Proposal, address: Address): Unit = {
  ///   if (!proposalIsValid(p, address)) {
  ///     log.warning(s"invalid proposal, proposal view: ${p.view}, current view: $currentView, proposal from: $address")
  ///   } else {
  ///     proposals += (p.view -> p)
  ///     if ((currentView == p.view) && isAt(ProposalStage)) {
  ///       /// TODO: check all prevotes at polc-round if we have polc
  ///       currentStage = PrevoteStage
  ///       toStage(PrevoteStage)
  ///     } else {
  ///       commonExit()
  ///     }
  ///   }
  /// }

  private [this] def processProposal(p: SignedProposal): Unit = {
    val address = getAddress(p.publicKey)
    if (!proposalIsValid(p.proposal, address)) {
      log.warning(s"invalid proposal, proposal view: ${p.proposal.view}, current view: $currentView, proposal from: $address")
    } else {
      proposals += (p.consensusMessage.view -> p)
      val proposal = p.proposal
      if ((currentView == proposal.view) && isAt(ProposalStage)) {
        /// TODO: check all prevotes at polc-round if we have polc
        currentStage = PrevoteStage
        toStage(PrevoteStage)
      } else {
        commonExit()
      }
    }
  }

  private [this] def needUnlock(round: Round): Boolean = {
    lastLockRound match {
      case Some(r) => r < round && round < currentRound
      case _ => false
    }
  }

  private [this] def tryUnlock(): Unit = {
    newPolc match {
      case Some((step, _)) =>
        if (needUnlock(step._2)) {
          polc = None
          lastLockRound = None
        }
      case None =>
    }
  }

  /// private [this] def processPrevote(p: Prevote): Unit = {
  ///   if (p.view < currentView) {
  ///     log.info(s"prevote view ${p.view} less than current view $currentView, we don't process")
  ///   } else {
  ///     voteSet.insert(p)
  ///     if (isAt(PrevoteStage)) {
  ///       if (hasEnoughVote(PrevoteStage)) {
  ///         currentStage = PrecommitStage
  ///         toStage(PrecommitStage)
  ///       } else {
  ///         commonExit()
  ///       }
  ///     }
  ///   }
  /// }

  private [this] def processPrevote(p: SignedPrevote): Unit = {
    if (p.consensusMessage.view < currentView) {
      log.info(s"prevote view ${p.consensusMessage.view} less than current view $currentView, ignore this")
    } else {
      voteSet.insert(p)
      if (isAt(PrevoteStage)) {
        if (hasEnoughVote(PrevoteStage)) {
          currentStage = PrecommitStage
          toStage(PrecommitStage)
        } else {
          commonExit()
        }
      }
    }
  }

  /// private [this] def processPrecommit(p: Precommit): Unit = {
  ///   if (p.view < currentView) {
  ///     log.info(s"precommit view ${p.view} less than current view $currentView, we don't process")
  ///   } else {
  ///     voteSet.insert(p)
  ///     if (isAt(PrecommitStage)) {
  ///       if (hasEnoughVoteForNil) {
  ///         currentStage = ProposalStage
  ///         currentRound += 1
  ///         toStage(ProposalStage)
  ///       } else {
  ///         commonExit()
  ///       }
  ///     }
  ///   }
  /// }

  private [this] def processPrecommit(p: SignedPrecommit): Unit = {
    if (p.consensusMessage.view < currentView) {
      log.info(s"precommit view ${p.consensusMessage.view} less than current view $currentView, we don't process")
    } else {
      /// TODO: may be we should check wheather the step equal with PrecommitStage or not
      /// require(p.consensusMessage.step._3 == PrecommitStage)
      voteSet.insert(p)
      if (isAt(PrecommitStage)) {
        if (hasEnoughVoteForNil) {
          currentStage = ProposalStage
          currentRound += 1
          toStage(ProposalStage)
        } else {
          commonExit()
        }
      }
    }
  }

  private [this] def commonExit(): Unit = {
    if (hasEnoughVote(PrecommitStage)) {
      currentStage = CommitStage
      toStage(CommitStage)
    } else {
      hasEnoughAnyVoteAtStage(PrevoteStage) match {
        case Some(round) =>
          currentStage = PrevoteStage
          currentRound = round
          toStage(PrevoteStage)
        case None => hasEnoughAnyVoteAtStage(PrecommitStage) foreach { round =>
          currentStage = PrecommitStage
          currentRound = round
          toStage(PrecommitStage)
        }
      }
    }
  }

  private [this] def processProposalTimeout(timeout: ProposalTimeout): Unit = {
    val proposalView = (timeout.height, timeout.round)
    if ((currentView == proposalView) && isAt(ProposalStage)) {
      currentStage = PrevoteStage
      toStage(PrevoteStage)
    }
  }

  private [this] def processPrevoteTimeout(timeout: PrevoteTimeout): Unit = {
    val prevoteView = (timeout.height, timeout.round)
    if ((currentView == prevoteView) && isAt(PrevoteStage) ) {
      if (hasEnoughAnyVoteAtRound(currentRound, PrevoteStage)) {
        currentStage = PrecommitStage
        toStage(PrecommitStage)
      } else {
        /// Now, prevote timeout, but there are no enough votes, maybe we offline
      }
    }
  }

  private [this] def processPrecommitTimeout(timeout: PrecommitTimeout): Unit = {
    val precommitView = (timeout.height, timeout.round)
    if ((currentView == precommitView) && isAt(PrecommitStage)) {
      if (hasEnoughAnyVoteAtRound(currentRound, PrecommitStage)) {
        currentStage = ProposalStage
        currentRound += 1
        toStage(ProposalStage)
      } else {
        /// Now, precommit timeout, but there are no enough votes, maybe we offline
      }
    }
  }

  private [this] def processSignedMessage(signedMessage: SignedConsensusMessage): Unit = {
    if (signedMessage.isValid) {
      signedMessage match {
        case proposal: SignedProposal =>
          log.info(s"receive proposal from ${getAddress(proposal.publicKey)}")
          processProposal(proposal)
        case prevote: SignedPrevote =>
          log.info(s"receive prevote from ${getAddress(prevote.publicKey)}")
          processPrevote(prevote)
        case precommit: SignedPrecommit =>
          log.info(s"receive precommit from ${getAddress(precommit.publicKey)}")
          processPrecommit(precommit)
      }
    }
  }

  def receive = {
    /// TODO: refactor
    case signedMessage: SignedConsensusMessage =>
      processSignedMessage(signedMessage)
    case timeout: Timeout =>
      log.info(s"$timeout out of time")
      timeout match {
        case timeout: ProposalTimeout => processProposalTimeout(timeout)
        case timeout: PrevoteTimeout => processPrevoteTimeout(timeout)
        case timeout: PrecommitTimeout => processPrecommitTimeout(timeout)
    }
    case Ping =>
      /// TODO: signed message is necessary, because we could not distinguish whether peer node is valid or not
      log.info(s"receive $sender ping")
      sender ! Pong
    case Pong =>
      log.info(s"receive $sender pong")
      peers += sender
    case msg =>
      log.error(s"receive unknown message $msg")
  }
}

object Tendermint {
  sealed trait ConnectedMessage extends Message
  object Ping extends ConnectedMessage
  object Pong extends ConnectedMessage

  def props(keyPair: KeyPair, actors: Seq[String], peerPublicKeys: Seq[PublicKey]): Props =
    Props(new Tendermint(keyPair, actors, peerPublicKeys))

  /// unused currently
  def getPeersFromConfig(path: String): Seq[String] = {
    val config = ConfigFactory.load(path)
    val list = JavaConverters.asScalaIteratorConverter(config.getStringList("consensus.peers").iterator()).asScala.toSeq
    require(list.size == list.distinct.size, "consensus.peers in your config have duplicate actor path")
    list
  }
}
