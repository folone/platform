/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package actor 

import com.precog.common._

import akka.actor.Actor
import akka.actor.Scheduler
import akka.actor.ActorRef
import akka.dispatch.Await
import akka.dispatch.Future
import akka.dispatch.ExecutionContext
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import akka.util.Duration

import blueeyes.json.JsonAST._

import com.weiglewilczek.slf4s._

import annotation.tailrec
import collection.mutable
import java.util.concurrent.atomic.AtomicLong

import scalaz._
import scalaz.syntax.bind._

//////////////
// MESSAGES //
//////////////

case class GetMessages(sendTo: ActorRef)

sealed trait IngestResult
case class IngestErrors(errors: Seq[String]) extends IngestResult
case class IngestData(messages: Seq[IngestMessage]) extends IngestResult

case class DirectIngestData(messages: Seq[IngestMessage])

////////////
// ACTORS //
////////////

/**
 * The purpose of the ingest supervisor is to coordinate multiple sources of data from which data
 * may be ingested. At present, there are two such sources: the inbound kafka queue, represented
 * by the ingestActor, and the "manual" ingest pipeline which may send direct ingest requests to
 * this actor. 
 */
class IngestSupervisor(ingestActor: Option[ActorRef], projectionsActor: ActorRef, routingTable: RoutingTable, 
                       idleDelay: Duration, scheduler: Scheduler, shutdownCheck: Duration) extends Actor with Logging {

  private var initiated = 0
  private var processed = 0
  private var errors = 0

  override def preStart() = {
    logger.info("Starting IngestSupervisor against IngestActor " + ingestActor)
    if (ingestActor.isDefined) {
      scheduleIngestRequest(Duration.Zero)
      logger.info("Initial ingest request scheduled")
    }
  }

  override def postStop() = {
    logger.info("IngestSupervisor shutting down")
  }

  def receive = {
    case Status =>
      logger.debug("Ingest supervisor status")
      sender ! status

    case IngestErrors(messages) => 
      errors += 1
      messages.foreach(logger.error(_))
      scheduleIngestRequest(idleDelay)

    case IngestData(messages)   => 
      processed += 1
      if (messages.isEmpty) {
        scheduleIngestRequest(idleDelay)
      } else {
        logger.debug("Ingesting " + messages.size + " messages")
        processMessages(messages, sender)
        scheduleIngestRequest(Duration.Zero)
      }

    case DirectIngestData(d) =>
      logger.info("Processing direct ingest of " + d.size + " messages")
      processMessages(d, sender) 
  }

  private def status: JValue = JObject(JField("Ingest Actor Present", JBool(ingestActor.isDefined)) ::
                                       JField("Routing", JObject(JField("initiated", JInt(initiated)) :: 
                                                                 JField("processed", JInt(processed)) :: Nil)) :: Nil)

  private def processMessages(messages: Seq[IngestMessage], batchCoordinator: ActorRef): Unit = {
    val inserts = routingTable.batchMessages(messages)

    logger.debug("Sending " + inserts.size + " messages for insert")
    batchCoordinator ! ProjectionInsertsExpected(inserts.size)
    for (insert <- inserts) projectionsActor.tell(insert, batchCoordinator)
  }

  private def scheduleIngestRequest(delay: Duration): Unit = {
    ingestActor match {
      case Some(actor) => 
        initiated += 1
        scheduler.scheduleOnce(delay, actor, GetMessages(self))

      case None => 
        logger.warn("Attempted to schedule ingest request after delay " + delay + " but no ingest actor reference present.")
    }
  }
}
