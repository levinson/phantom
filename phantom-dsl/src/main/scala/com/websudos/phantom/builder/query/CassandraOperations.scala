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

import com.datastax.driver.core.{ProtocolVersion, ResultSet, Session, Statement}
import com.google.common.util.concurrent.{FutureCallback, Futures}
import com.twitter.util.{Future => TwitterFuture, Promise => TwitterPromise, Return, Throw}
import com.websudos.phantom.Manager
import com.websudos.phantom.connectors.KeySpace

import scala.concurrent.{Future => ScalaFuture, Promise => ScalaPromise}

private[phantom] trait SessionAugmenter {
  implicit class RichSession(val session: Session) {
    def protocolVersion: ProtocolVersion = {
      session.getCluster.getConfiguration.getProtocolOptions.getProtocolVersion
    }

    def isNewerThan(pv: ProtocolVersion): Boolean = {
      protocolVersion.compareTo(pv) > 0
    }

    def v3orNewer : Boolean = isNewerThan(ProtocolVersion.V2)
  }
}

private[phantom] trait CassandraOperations extends SessionAugmenter {

  protected[this] def scalaQueryStringExecuteToFuture(st: Statement)(
      implicit session: Session, keyspace: KeySpace, executor: Executor): ScalaFuture[ResultSet] = {
    scalaQueryStringToPromise(st).future
  }

  protected[this] def scalaQueryStringToPromise(st: Statement)(
      implicit session: Session, keyspace: KeySpace, executor: Executor): ScalaPromise[ResultSet] = {
    Manager.logger.debug(s"Executing query: ${st.toString}")

    val promise = ScalaPromise[ResultSet]()

    val future = session.executeAsync(st)

    val callback = new FutureCallback[ResultSet] {
      def onSuccess(result: ResultSet): Unit = {
        promise success result
      }

      def onFailure(err: Throwable): Unit = {
        Manager.logger.error(err.getMessage)
        promise failure err
      }
    }
    Futures.addCallback(future, callback, Manager.executor)
    promise
  }


  protected[this] def twitterQueryStringExecuteToFuture(str: Statement)(implicit session: Session, keyspace: KeySpace): TwitterFuture[ResultSet] = {
    Manager.logger.debug(s"Executing query: $str")

    val promise = TwitterPromise[ResultSet]()
    val future = session.executeAsync(str)

    val callback = new FutureCallback[ResultSet] {
      def onSuccess(result: ResultSet): Unit = {
        promise update Return(result)
      }

      def onFailure(err: Throwable): Unit = {
        Manager.logger.error(err.getMessage)
        promise update Throw(err)
      }
    }
    Futures.addCallback(future, callback, Manager.executor)
    promise
  }
}
