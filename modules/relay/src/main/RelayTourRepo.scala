package lila.relay

import org.joda.time.DateTime
import reactivemongo.api.bson._
import reactivemongo.api.ReadPreference

import lila.db.dsl._

final private class RelayTourRepo(val coll: Coll)(implicit ec: scala.concurrent.ExecutionContext) {

  import BSONHandlers._

  def lookup(local: String) = $lookup.simple(coll, "tour", local, "_id")

  private[relay] object selectors {
    def officialOption(v: Boolean) = $doc("official" -> v.option(true))

  }
}