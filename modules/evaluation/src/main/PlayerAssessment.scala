package lila.evaluation

import chess.{ Color, Speed }
import lila.analyse.{ Accuracy, Analysis }
import lila.game.{ Game, Player, Pov }
import org.joda.time.DateTime
import lila.user.User

case class PlayerAssessment(
    _id: String,
    gameId: Game.ID,
    userId: User.ID,
    color: Color,
    assessment: GameAssessment,
    date: DateTime,
    basics: PlayerAssessment.Basics,
    analysis: Statistics.IntAvgSd,
    flags: PlayerFlags,
    tcFactor: Option[Double]
)

object PlayerAssessment {

  // when you don't have computer analysis
  case class Basics(
      moveTimes: Statistics.IntAvgSd,
      hold: Boolean,
      blurs: Int,
      blurStreak: Option[Int],
      mtStreak: Option[Boolean]
  )

  def make(pov: Pov, analysis: Analysis, holdAlerts: Player.HoldAlert.Map): PlayerAssessment = {
    import Statistics._
    import pov.{ color, game }

    lazy val suspiciousErrorRate: Boolean =
      listAverage(Accuracy.diffsList(pov, analysis)) < (game.speed match {
        case Speed.Bullet => 25
        case Speed.Blitz  => 20
        case _            => 15
      })

    lazy val alwaysHasAdvantage: Boolean =
      !analysis.infos.exists { info =>
        info.cp.fold(info.mate.fold(false) { a =>
          a.signum == color.fold(-1, 1)
        }) { cp =>
          color.fold(cp.centipawns < -100, cp.centipawns > 100)
        }
      }

    lazy val highBlurRate: Boolean =
      !game.isSimul && game.playerBlurPercent(color) > 90

    lazy val moderateBlurRate: Boolean =
      !game.isSimul && game.playerBlurPercent(color) > 70

    lazy val suspiciousHoldAlert: Boolean =
      holdAlerts(color).exists(_.suspicious)

    lazy val highestChunkBlurs: Int =
      game.player(color).blurs.booleans.sliding(12).map(_.count(identity)).max

    lazy val highChunkBlurRate: Boolean =
      highestChunkBlurs >= 11

    lazy val moderateChunkBlurRate: Boolean =
      highestChunkBlurs >= 8

    lazy val highlyConsistentMoveTimes: Boolean =
      game.clock.exists(_.estimateTotalSeconds > 60) && {
        moveTimeCoefVariation(pov) ?? cvIndicatesHighlyFlatTimes
      }

    // moderatelyConsistentMoveTimes must stay in Statistics because it's used in classes that do not use Assessible

    lazy val highlyConsistentMoveTimeStreaks: Boolean =
      game.clock.exists(_.estimateTotalSeconds > 60) && {
        slidingMoveTimesCvs(pov) ?? {
          _ exists cvIndicatesHighlyFlatTimesForStreaks
        }
      }

    lazy val flags: PlayerFlags = PlayerFlags(
      suspiciousErrorRate,
      alwaysHasAdvantage,
      highBlurRate || highChunkBlurRate,
      moderateBlurRate || moderateChunkBlurRate,
      highlyConsistentMoveTimes || highlyConsistentMoveTimeStreaks,
      moderatelyConsistentMoveTimes(pov),
      noFastMoves(pov),
      suspiciousHoldAlert
    )

    val T = true
    val F = false

    def assessment: GameAssessment = {
      import GameAssessment._
      val assessment = flags match {
        //               SF1 SF2 BLR1 BLR2 HCMT MCMT NFM Holds
        case PlayerFlags(T, _, T, _, _, _, T, _) => Cheating // high accuracy, high blurs, no fast moves
        case PlayerFlags(T, _, _, T, _, _, _, _) => Cheating // high accuracy, moderate blurs
        case PlayerFlags(T, _, _, _, T, _, _, _) => Cheating // high accuracy, highly consistent move times
        case PlayerFlags(_, _, T, _, T, _, _, _) => Cheating // high blurs, highly consistent move times

        case PlayerFlags(_, _, _, T, _, T, _, _) => LikelyCheating // moderate blurs, consistent move times
        case PlayerFlags(T, _, _, _, _, _, _, T) => LikelyCheating // Holds are bad, hmk?
        case PlayerFlags(_, T, _, _, _, _, _, T) => LikelyCheating // Holds are bad, hmk?
        case PlayerFlags(_, _, _, _, T, _, _, _) => LikelyCheating // very consistent move times
        case PlayerFlags(_, T, T, _, _, _, _, _) => LikelyCheating // always has advantage, high blurs

        case PlayerFlags(_, T, _, _, _, T, T, _) => Unclear // always has advantage, consistent move times
        case PlayerFlags(T, _, _, _, _, T, T, _) =>
          Unclear // high accuracy, consistent move times, no fast moves
        case PlayerFlags(T, _, _, F, _, F, T, _) =>
          Unclear // high accuracy, no fast moves, but doesn't blur or flat line

        case PlayerFlags(T, _, _, _, _, _, F, _) => UnlikelyCheating // high accuracy, but has fast moves

        case PlayerFlags(F, F, _, _, _, _, _, _) => NotCheating // low accuracy, doesn't hold advantage
        case _                                   => NotCheating
      }

      if (flags.suspiciousHoldAlert) assessment
      else if (~game.wonBy(color)) assessment
      else if (assessment == Cheating) LikelyCheating
      else if (assessment == LikelyCheating) Unclear
      else assessment
    }

    val tcFactor: Double = game.speed match {
      case Speed.Bullet | Speed.Blitz => 1.25
      case Speed.Rapid                => 1.0
      case Speed.Classical            => 0.6
      case _                          => 1.0
    }

    PlayerAssessment(
      _id = s"${game.id}/${color.name}",
      gameId = game.id,
      userId = ~game.player(color).userId,
      color = color,
      assessment = assessment,
      date = DateTime.now,
      Basics(
        moveTimes = Statistics.intAvgSd(~game.moveTimes(color) map (_.roundTenths)),
        blurs = game playerBlurPercent color,
        hold = suspiciousHoldAlert,
        blurStreak = highestChunkBlurs.some.filter(0 <),
        mtStreak = highlyConsistentMoveTimeStreaks.some.filter(identity)
      ),
      analysis = Statistics.intAvgSd(Accuracy.diffsList(pov, analysis)),
      flags = flags,
      tcFactor = tcFactor.some
    )
  }
}
