package tamer
package db

import doobie.Query0
import doobie.implicits._
import doobie.util.transactor.Transactor
import fs2.Stream
import log.effect.zio.ZioLogWriter.log4sFromName
import zio._
import zio.duration._
import zio.interop.catz._
import zio.stream.ZStream

import java.time.Instant

sealed abstract case class DbSetup[K, V, S: Hashable](
    serdes: Setup.Serdes[K, V, S],
    initialState: S,
    recordKey: (S, V) => K,
    query: S => Query0[V],
    stateFold: (S, QueryResult[V]) => UIO[S]
) extends Setup[Has[Transactor[Task]] with Has[QueryConfig], K, V, S] {

  private[this] final val sql              = query(initialState).sql
  private[this] final val queryHash        = sql.hash
  private[this] final val initialStateHash = initialState.hash

  override final val stateKey = queryHash + initialStateHash
  override final val repr: String =
    s"""query:              $sql
       |query hash:         $queryHash
       |initial state:      $initialState
       |initial state hash: $initialStateHash
       |state key:          $stateKey
       |""".stripMargin


  private[this] final val logTask = log4sFromName.provide("tamer.db")
  override final val monitor      = StateMonitor.noOp

  private[this] final def process(query: Query0[V], chunkSize: Int, tx: Transactor[Task], state: S): Task[(List[ValueWithMetadata[(K, V)]], Long)] =
    query
      .streamWithChunkSize(chunkSize)
      .chunks
      .transact(tx)
      .map(ChunkWithMetadata(_))
      .map { c =>
        ChunkWithMetadata(c.chunk.map(v => recordKey(state, v) -> v), c.pulledAt)
      }
      .flatMap(c => Stream.chunk(c.chunk).map(ValueWithMetadata(_, c.pulledAt)))
      .compile
      .toList
      .map(values => values -> values.headOption.map(_.pulledAt).getOrElse(System.nanoTime()))

  override def iteration(currentState: S): ZStream[Has[Transactor[Task]] with Has[QueryConfig], Throwable, (Option[NonEmptyChunk[(K, V)]], S)] =
    ZStream.fromEffect {
      for {
        log            <- logTask
        transactor     <- ZIO.service[Transactor[Task]]
        chunkSize      <- ZIO.service[QueryConfig].map(_.fetchChunkSize)
        query          <- UIO(query(currentState))
        _              <- log.debug(s"running ${query.sql} with params derived from $currentState")
        start          <- UIO(System.nanoTime())
        (values, time) <- process(query, chunkSize, transactor, currentState)
        newState       <- stateFold(currentState, QueryResult(ResultMetadata(time - start), values.map(_.value._2)))
      } yield (NonEmptyChunk.fromIterableOption(values.map(_.value)), newState)
    }
}

object DbSetup {
  final def apply[K: Codec, V: Codec, S: Codec: Hashable](
      initialState: S
  )(
      query: S => Query0[V]
  )(
      recordKey: (S, V) => K,
      stateFold: (S, QueryResult[V]) => UIO[S]
  )(
      implicit ev: Codec[Tamer.StateKey]
  ): DbSetup[K, V, S] = new DbSetup(Setup.mkSerdes[K, V, S], initialState, recordKey, query, stateFold) {}

  final def tumbling[K: Codec, V <: Timestamped: Ordering: Codec](
      query: Window => Query0[V]
  )(
      recordKey: (Window, V) => K,
      from: Instant = Instant.now,
      tumblingStep: Duration = 5.minutes,
      lag: Duration = 0.seconds
  )(
      implicit ev0: Codec[Tamer.StateKey],
      ev1: Codec[Window]
  ): DbSetup[K, V, Window] = {
    def stateFold(currentWindow: Window, queryResult: QueryResult[V]): UIO[Window] =
      if (queryResult.results.isEmpty) {
        UIO((currentWindow.to + tumblingStep).orNow(lag)).map(Window(currentWindow.from, _))
      } else {
        val mostRecent = queryResult.results.max.timestamp
        UIO((mostRecent + tumblingStep).orNow(lag)).map(Window(mostRecent, _))
      }

    DbSetup(Window(from, from + tumblingStep))(query)(recordKey, stateFold)
  }
}
