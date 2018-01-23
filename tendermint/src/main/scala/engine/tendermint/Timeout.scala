package engine
package tendermint

import tendermint.Stage.{Height, Round}
import scala.concurrent.duration.{Duration, MILLISECONDS}

sealed trait Timeout {
  def duration: Duration
}
/// Proposal timeout will increment according to round
case class ProposalTimeout(height: Height, round: Round) extends Timeout {
  val milliseconds: Long = 1000 * (round + 1)
  def duration = Duration(milliseconds, MILLISECONDS)
}
case class PrevoteTimeout(height: Height, round: Round) extends Timeout {
  def duration = Duration(1000, MILLISECONDS)
}
case class PrecommitTimeout(height: Height, round: Round) extends Timeout {
  def duration = Duration(1000, MILLISECONDS)
}
/// Maybe we don't need this
/// case object CommitTimeout extends Timeout {
///   def timeout = Duration(1000, MILLISECONDS)
/// }

