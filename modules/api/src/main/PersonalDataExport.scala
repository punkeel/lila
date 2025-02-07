package lila.api

import akka.stream.Materializer
import akka.stream.scaladsl._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import reactivemongo.akkastream.cursorProducer
import reactivemongo.api.ReadPreference
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import lila.chat.Chat
import lila.db.dsl._
import lila.game.Game
import lila.user.User

final class PersonalDataExport(
    securityEnv: lila.security.Env,
    msgEnv: lila.msg.Env,
    forumEnv: lila.forum.Env,
    gameEnv: lila.game.Env,
    roundEnv: lila.round.Env,
    chatEnv: lila.chat.Env,
    relationEnv: lila.relation.Env,
    userRepo: lila.user.UserRepo,
    ublogApi: lila.ublog.UblogApi,
    picfitUrl: lila.memo.PicfitUrl,
    mongoCacheApi: lila.memo.MongoCache.Api
)(implicit ec: ExecutionContext, mat: Materializer) {

  private val lightPerSecond = 60
  private val heavyPerSecond = 30

  def apply(user: User): Source[String, _] = {

    val intro =
      Source.futureSource {
        userRepo.currentOrPrevEmail(user.id) map { email =>
          Source(
            List(
              textTitle(s"Personal data export for ${user.username}"),
              "All dates are UTC",
              bigSep,
              s"Signup date: ${textDate(user.createdAt)}",
              s"Last seen: ${user.seenAt ?? textDate}",
              s"Public profile: ${user.profile.??(_.toString)}",
              s"Email: ${email.??(_.value)}"
            )
          )
        }
      }

    val connections =
      Source(List(textTitle("Connections"))) concat
        securityEnv.store.allSessions(user.id).documentSource().throttle(lightPerSecond, 1 second).map { s =>
          s"${s.date.??(textDate)} ${s.ip} ${s.ua}"
        }

    val followedUsers =
      Source.futureSource {
        relationEnv.api.fetchFollowing(user.id) map { userIds =>
          Source(List(textTitle("Followed players")) ++ userIds)
        }
      }

    val forumPosts =
      Source(List(textTitle("Forum posts"))) concat
        forumEnv.postApi.allByUser(user.id).documentSource().throttle(heavyPerSecond, 1 second).map { p =>
          s"${textDate(p.createdAt)}\n${p.text}$bigSep"
        }

    val privateMessages =
      Source(List(textTitle("Direct messages"))) concat
        msgEnv.api
          .allMessagesOf(user.id)
          .throttle(heavyPerSecond, 1 second)
          .map { case (text, date) =>
            s"${textDate(date)}\n$text$bigSep"
          }

    def gameChatsLookup(lookup: Bdoc) =
      gameEnv.gameRepo.coll
        .aggregateWith[Bdoc](readPreference = ReadPreference.secondaryPreferred) { framework =>
          import framework._
          List(
            Match($doc(Game.BSONFields.playerUids -> user.id)),
            Project($id(true)),
            PipelineOperator(lookup),
            Unwind("chat"),
            ReplaceRootField("chat"),
            Project($doc("_id" -> false, "l" -> true)),
            Unwind("l"),
            Match("l".$startsWith(s"${user.id} ", "i"))
          )
        }
        .documentSource()
        .map { doc => doc.string("l").??(_.drop(user.id.size + 1)) }
        .throttle(heavyPerSecond, 1 second)

    val privateGameChats =
      Source(List(textTitle("Private game chat messages"))) concat
        gameChatsLookup(
          $doc(
            "$lookup" -> $doc(
              "from"         -> chatEnv.coll.name,
              "as"           -> "chat",
              "localField"   -> "_id",
              "foreignField" -> "_id"
            )
          )
        )

    val spectatorGameChats =
      Source(List(textTitle("Spectator game chat messages"))) concat
        gameChatsLookup(
          $doc(
            "$lookup" -> $doc(
              "from"     -> chatEnv.coll.name,
              "as"       -> "chat",
              "let"      -> $doc("id" -> $doc("$concat" -> $arr("$_id", "/w"))),
              "pipeline" -> $arr($doc("$match" -> $doc("$expr" -> $doc("$eq" -> $arr("$_id", "$$id")))))
            )
          )
        )

    val gameNotes =
      Source(List(textTitle("Game notes"))) concat
        gameEnv.gameRepo.coll
          .aggregateWith[Bdoc](
            readPreference = ReadPreference.secondaryPreferred
          ) { framework =>
            import framework._
            List(
              Match($doc(Game.BSONFields.playerUids -> user.id)),
              Project($id(true)),
              PipelineOperator(
                $doc(
                  "$lookup" -> $doc(
                    "from"     -> roundEnv.noteApi.collName,
                    "as"       -> "note",
                    "let"      -> $doc("id" -> $doc("$concat" -> $arr("$_id", user.id))),
                    "pipeline" -> $arr($doc("$match" -> $doc("$expr" -> $doc("$eq" -> $arr("$_id", "$$id")))))
                  )
                )
              ),
              Unwind("note"),
              ReplaceRootField("note"),
              Project($doc("_id" -> false, "t" -> true))
            )
          }
          .documentSource()
          .map { doc => ~doc.string("t") }
          .throttle(heavyPerSecond, 1 second)

    val ublogPosts =
      Source(List(textTitle("Blog posts"))) concat
        ublogApi
          .postCursor(user)
          .documentSource()
          .map { post =>
            List(
              "date"   -> textDate(post.created.at),
              "title"  -> post.title,
              "intro"  -> post.intro,
              "body"   -> post.markdown,
              "image"  -> post.image.??(lila.ublog.UblogPost.thumbnail(picfitUrl, _, _.Large)),
              "topics" -> post.topics.map(_.value).mkString(", ")
            ).map { case (k, v) =>
              s"$k: $v"
            }.mkString("\n") + bigSep
          }
          .throttle(heavyPerSecond, 1 second)

    val outro = Source(List(textTitle("End of data export.")))

    List(
      intro,
      connections,
      followedUsers,
      ublogPosts,
      forumPosts,
      privateMessages,
      privateGameChats,
      spectatorGameChats,
      gameNotes,
      outro
    ).foldLeft(Source.empty[String])(_ concat _)
  }

  private val bigSep = "\n------------------------------------------\n"

  private def textTitle(t: String) = s"\n${"=" * t.length}\n$t\n${"=" * t.length}\n"

  private val englishDateTimeFormatter = DateTimeFormat forStyle "MS"
  private def textDate(date: DateTime) = englishDateTimeFormatter print date
}
