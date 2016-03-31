/*
 * Copyright 2013-2015 Websudos, Limited.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Explicit consent must be obtained from the copyright owner, Websudos Limited before any redistribution is made.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.websudos.phantom.builder.query

import java.util.concurrent.Executor
import java.util.{List => JavaList}

import com.datastax.driver.core._
import com.twitter.concurrent.Spool
import com.twitter.util.{Future => TwitterFuture}
import com.websudos.phantom.CassandraTable
import com.websudos.phantom.builder.{LimitBound, Unlimited}
import com.websudos.phantom.connectors.KeySpace
import com.websudos.phantom.iteratee.{Enumerator, ResultSpool}
import play.api.libs.iteratee.{Enumeratee, Enumerator => PlayEnumerator}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future => ScalaFuture}

trait ExecutableStatement extends CassandraOperations {

  def options: QueryOptions

  def qb: CQLQuery

  def queryString: String = qb.terminate().queryString

  def statement()(implicit session: Session): Statement = {
    new SimpleStatement(qb.terminate().queryString)
      .setConsistencyLevel(options.consistencyLevel.orNull)
  }

  /**
    * Default asynchronous query execution method. This will convert the underlying
    * call to Cassandra done with Google Guava ListenableFuture to a consumable
    * Scala Future that will be completed once the operation is completed on the
    * database end.
    *
    * The execution context of the transformation is provided by phantom via
    * [[com.websudos.phantom.Manager.scalaExecutor]] and it is recommended to
    * use [[com.websudos.phantom.dsl.context]] for operations that chain
    * database calls.
    *
    * @param session The implicit session provided by a [[com.websudos.phantom.connectors.Connector]].
    * @param keySpace The implicit keySpace definition provided by a [[com.websudos.phantom.connectors.Connector]].
    * @return
    */
  def future()(implicit session: Session, keySpace: KeySpace, executor: Executor, context: ExecutionContext): ScalaFuture[ResultSet] = {
    scalaQueryStringExecuteToFuture(statement)
  }

  /**
    * This will convert the underlying call to Cassandra done with Google Guava ListenableFuture to a consumable
    * Scala Future that will be completed once the operation is completed on the
    * database end.
    *
    * The execution context of the transformation is provided by phantom via
    * [[com.websudos.phantom.Manager.scalaExecutor]] and it is recommended to
    * use [[com.websudos.phantom.dsl.context]] for operations that chain
    * database calls.
    *
    * @param modifyStatement The function allowing to modify underlying [[Statement]]
    * @param session The implicit session provided by a [[com.websudos.phantom.connectors.Connector]].
    * @param keySpace The implicit keySpace definition provided by a [[com.websudos.phantom.connectors.Connector]].
    * @return
    */
  def future(modifyStatement : Statement => Statement)(
      implicit session: Session, keySpace: KeySpace, executor: Executor, context: ExecutionContext): ScalaFuture[ResultSet] = {
    scalaQueryStringExecuteToFuture(modifyStatement(statement))
  }

  /**
    * Default asynchronous query execution method based on Twitter Future API. This will convert the underlying
    * call to Cassandra done with Google Guava ListenableFuture to a consumable
    * Twitter Future that will be completed once the operation is completed on the
    * database end.
    *
    * Unlike Scala Futures, Twitter Futures and operations on them do not require an implicit context.
    * Instead, the context propagates from one future to another inside a flatMap chain which means
    * all operations(map, flatMap) that originate on a Twitter Future obtained as the result of a database
    * call will execute inside [[com.websudos.phantom.Manager.executor]].
    *
    * @param session The implicit session provided by a [[com.websudos.phantom.connectors.Connector]].
    * @param keySpace The implicit keySpace definition provided by a [[com.websudos.phantom.connectors.Connector]].
    * @return
    */
  def execute()(implicit session: Session, keySpace: KeySpace): TwitterFuture[ResultSet] = {
    twitterQueryStringExecuteToFuture(statement)
  }
}

private[phantom] class ExecutableStatementList(val list: Seq[CQLQuery]) extends CassandraOperations {

  /**
   * Secondary constructor to allow passing in Sets instead of Sequences.
   * Although this may appear to be fruitless and uninteresting it a necessary evil.
   *
   * The TwitterFuture.collect method does not support passing in arbitrary collections using the Scala API
   * just as Scala.future does. Scala Futures can sequence over traversables and return a collection of the appropiate type.
   *
   * @param queries The list of CQL queries to execute.
   * @return An instance of an ExecutableStatement with the matching sequence of CQL queries.
   */
  def this(queries: Set[CQLQuery]) = this(queries.toSeq)

  def ++(appendable: Seq[CQLQuery]): ExecutableStatementList = {
    new ExecutableStatementList(list ++ appendable)
  }

  def ++(st: ExecutableStatementList): ExecutableStatementList = {
    ++(st.list)
  }

  def future()(implicit session: Session, keySpace: KeySpace, executor: Executor, context: ExecutionContext): ScalaFuture[Seq[ResultSet]] = {
    ScalaFuture.sequence(list.map(item => scalaQueryStringExecuteToFuture(new SimpleStatement(item.terminate().queryString))))
  }

  def execute()(implicit session: Session, keySpace: KeySpace): TwitterFuture[Seq[ResultSet]] = {
    TwitterFuture.collect(list.map(item => twitterQueryStringExecuteToFuture(new SimpleStatement(item.terminate().queryString))))
  }
}

/**
 * An ExecutableQuery implementation, meant to retrieve results from Cassandra.
 * This provides the root implementation of a Select query.
 * @tparam T The class owning the table.
 * @tparam R The record type to store.
 */
trait ExecutableQuery[T <: CassandraTable[T, _], R, Limit <: LimitBound] extends ExecutableStatement {

  def fromRow(r: Row): R

  private[this] def singleResult(row: Row): Option[R] = {
    if (Option(row).isDefined) Some(fromRow(row)) else None
  }

  private[this] def directMapper(results: JavaList[Row]): List[R] = {
    List.tabulate(results.size())(index => fromRow(results.get(index)))
  }

  private[phantom] def singleFetch()(implicit session: Session, keySpace: KeySpace, executor: Executor, context: ExecutionContext): ScalaFuture[Option[R]] = {
    future() map { res => singleResult(res.one) }
  }

  private[phantom] def singleCollect()(implicit session: Session, keySpace: KeySpace): TwitterFuture[Option[R]] = {
    execute() map { res => singleResult(res.one) }
  }

  /**
   * Produces an Enumerator for [R]ows
   * This enumerator can be consumed afterwards with an Iteratee
   * @param session The Cassandra session in use.
   * @return
   */
  def fetchEnumerator()(implicit session: Session, keySpace: KeySpace, executor: Executor, context: ExecutionContext): PlayEnumerator[R] = {
    val eventualEnum = future() map {
      resultSet => {
          Enumerator.enumerator(resultSet) through Enumeratee.map(r => fromRow(r))
      }
    }
    PlayEnumerator.flatten(eventualEnum)
  }

  /**
   * Produces a [[com.twitter.concurrent.Spool]] of [R]ows
   * A spool is both lazily constructed and consumed, suitable for large
   * collections when using twitter futures.
   * @param session The cassandra session in use.
   * @return A Spool of R.
   */
  def fetchSpool()(implicit session: Session, keySpace: KeySpace): TwitterFuture[Spool[R]] = {
    execute() flatMap {
      resultSet => ResultSpool.spool(resultSet).map(spool => spool map fromRow)
    }
  }

  /**
   * Returns the first row from the select ignoring everything else
   * @param session The Cassandra session in use.
   * @return A Scala future guaranteed to contain a single result wrapped as an Option.
   */
  def one()(implicit session: Session, keySpace: KeySpace, ev: Limit =:= Unlimited, ec: Executor, context: ExecutionContext): ScalaFuture[Option[R]]

  /**
   * Get the result of an operation as a Twitter Future.
   * @param session The Datastax Cassandra session.
   * @return A Twitter future wrapping the result.
   */
  def get()(implicit session: Session, keySpace: KeySpace, ev: Limit =:= Unlimited): TwitterFuture[Option[R]]

  /**
   * Returns a parsed sequence of [R]ows
   * This is not suitable for big results set
   * @param session The Cassandra session in use.
   * @return A Scala future wrapping a list of mapped results.
   */
  def fetch()(implicit session: Session, keySpace: KeySpace, executor: Executor, context: ExecutionContext): ScalaFuture[List[R]] = {
    future() map { resultSet => { directMapper(resultSet.all) } }
  }

  /**
   * Returns a parsed iterator of [R]ows
   * @param session The Cassandra session in use.
   * @return A Scala future wrapping scala iterator of mapped results.
   */
  def iterator()(implicit session: Session, keySpace: KeySpace, executor: Executor, context: ExecutionContext): ScalaFuture[Iterator[R]] = {
    future() map { _.iterator().asScala.map(fromRow) }
  }

  /**
   * Returns a parsed sequence of [R]ows
   * This is not suitable for big results set
   * @param session The Cassandra session in use.
   * @return A Twitter future wrapping a list of mapped results.
   */
  def collect()(implicit session: Session, keySpace: KeySpace): TwitterFuture[List[R]] = {
    execute() map { resultSet => { directMapper(resultSet.all) } }
  }
}
