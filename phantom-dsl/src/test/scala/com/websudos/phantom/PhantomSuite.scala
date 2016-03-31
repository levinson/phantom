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
package com.websudos.phantom

import java.util.concurrent.TimeUnit

import com.websudos.phantom.connectors.RootConnector
import com.websudos.phantom.tables.TestDatabase
import com.websudos.util.lift.{DateTimeSerializer, UUIDSerializer}
import org.scalatest._
import org.scalatest.concurrent.{PatienceConfiguration, ScalaFutures}

import scala.concurrent.duration._

trait PhantomBaseSuite extends Suite with Matchers
  with BeforeAndAfterAll
  with RootConnector
  with ScalaFutures
  with OptionValues {

  protected[this] val defaultScalaTimeoutSeconds = 10

  implicit val formats = net.liftweb.json.DefaultFormats + new UUIDSerializer + new DateTimeSerializer

  implicit val defaultScalaTimeout = scala.concurrent.duration.Duration(defaultScalaTimeoutSeconds, TimeUnit.SECONDS)

  implicit val defaultTimeout: PatienceConfiguration.Timeout = timeout(10.seconds)

  implicit val defaultPatience = PatienceConfig(timeout = 5.seconds, interval = 50.millis)

  implicit val executor = Manager.executor

  implicit val context = Manager.scalaExecutor
}

trait PhantomSuite extends FlatSpec with PhantomBaseSuite with TestDatabase.connector.Connector {

  val database = TestDatabase
}


trait PhantomFreeSuite extends FreeSpec with PhantomBaseSuite with TestDatabase.connector.Connector {
  val database = TestDatabase
}