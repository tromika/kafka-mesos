/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.kafka

import java.io._
import org.apache.log4j.Logger
import org.eclipse.jetty.server.{ServerConnector, Server}
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.servlet.{ServletHolder, ServletContextHandler}
import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}
import java.util
import scala.collection.JavaConversions._
import scala.util.parsing.json.{JSONArray, JSONObject}
import scala.collection.mutable.ListBuffer
import ly.stealth.mesos.kafka.Util.Period

object HttpServer {
  var jar: File = null
  var kafkaDist: File = null

  val logger = Logger.getLogger(HttpServer.getClass)
  var server: Server = null

  def start(resolveDeps: Boolean = true) {
    if (server != null) throw new IllegalStateException("started")
    if (resolveDeps) this.resolveDeps

    val threadPool = new QueuedThreadPool(16)
    threadPool.setName("Jetty")

    server = new Server(threadPool)
    val connector = new ServerConnector(server)
    connector.setPort(Config.schedulerPort)

    val handler = new ServletContextHandler
    handler.addServlet(new ServletHolder(new Servlet()), "/")

    server.setHandler(handler)
    server.addConnector(connector)
    server.start()

    logger.info("started on port " + connector.getPort)
  }

  def stop() {
    if (server == null) throw new IllegalStateException("!started")

    server.stop()
    server.join()
    server = null

    logger.info("stopped")
  }

  private def resolveDeps: Unit = {
    val jarMask: String = "kafka-mesos.*\\.jar"
    val kafkaMask: String = "kafka.*\\.tgz"

    for (file <- new File(".").listFiles()) {
      if (file.getName.matches(jarMask)) jar = file
      if (file.getName.matches(kafkaMask)) kafkaDist = file
    }

    if (jar == null) throw new IllegalStateException(jarMask + " not found in current dir")
    if (kafkaDist == null) throw new IllegalStateException(kafkaMask + " not found in in current dir")
  }

  class Servlet extends HttpServlet {
    override def doGet(request: HttpServletRequest, response: HttpServletResponse): Unit = {
      val uri = request.getRequestURI
      if (uri.startsWith("/executor/")) downloadFile(HttpServer.jar, response)
      else if (uri.startsWith("/kafka/")) downloadFile(HttpServer.kafkaDist, response)
      else if (uri.startsWith("/api/brokers")) handleBrokersApi(request, response)
      else response.sendError(404)
    }

    def downloadFile(file: File, response: HttpServletResponse): Unit = {
      response.setContentType("application/zip")
      response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName + "\"")
      Util.copyAndClose(new FileInputStream(file), response.getOutputStream)
    }

    def handleBrokersApi(request: HttpServletRequest, response: HttpServletResponse): Unit = {
      response.setContentType("application/json; charset=utf-8")
      var uri: String = request.getRequestURI.substring("/api/brokers".length)
      if (uri.startsWith("/")) uri = uri.substring(1)

      if (uri == "status") handleStatus(response)
      else if (uri == "add" || uri == "update") handleAddUpdateBroker(request, response)
      else if (uri == "remove") handleRemoveBroker(request, response)
      else if (uri == "start" || uri == "stop") handleStartStopBroker(request, response)
      else if (uri == "rebalance") handleRebalance(request, response)
      else response.sendError(404)
    }

    def handleStatus(response: HttpServletResponse): Unit = {
      response.getWriter.println("" + Scheduler.cluster.toJson)
    }

    def handleAddUpdateBroker(request: HttpServletRequest, response: HttpServletResponse): Unit = {
      val cluster = Scheduler.cluster
      val add: Boolean = request.getRequestURI.endsWith("add")
      val errors = new util.ArrayList[String]()

      val idExpr: String = request.getParameter("id")
      if (idExpr == null || idExpr.isEmpty) errors.add("id required")

      val host: String = request.getParameter("host")

      var cpus: java.lang.Double = null
      if (request.getParameter("cpus") != null)
        try { cpus = java.lang.Double.valueOf(request.getParameter("cpus")) }
        catch { case e: NumberFormatException => errors.add("Invalid cpus") }

      var mem: java.lang.Long = null
      if (request.getParameter("mem") != null)
        try { mem = java.lang.Long.valueOf(request.getParameter("mem")) }
        catch { case e: NumberFormatException => errors.add("Invalid mem") }

      var heap: java.lang.Long = null
      if (request.getParameter("heap") != null)
        try { heap = java.lang.Long.valueOf(request.getParameter("heap")) }
        catch { case e: NumberFormatException => errors.add("Invalid heap") }


      val options: String = request.getParameter("options")
      if (options != null)
        try { Util.parseMap(request.getParameter("options"), ";", "=") }
        catch { case e: IllegalArgumentException => errors.add("Invalid options") }

      val attributes: String = request.getParameter("attributes")
      if (attributes != null)
        try { Util.parseMap(request.getParameter("attributes"), ";", ":") }
        catch { case e: IllegalArgumentException => errors.add("Invalid attributes") }


      var failoverDelay: Period = null
      if (request.getParameter("failoverDelay") != null)
        try { failoverDelay = new Period(request.getParameter("failoverDelay")) }
        catch { case e: IllegalArgumentException => errors.add("Invalid failoverDelay") }

      var failoverMaxDelay: Period = null
      if (request.getParameter("failoverMaxDelay") != null)
        try { failoverMaxDelay = new Period(request.getParameter("failoverMaxDelay")) }
        catch { case e: IllegalArgumentException => errors.add("Invalid failoverMaxDelay") }

      val failoverMaxTries: String = request.getParameter("failoverMaxTries")
      if (failoverMaxTries != null && failoverMaxTries != "")
        try { Integer.valueOf(failoverMaxTries) }
        catch { case e: NumberFormatException => errors.add("Invalid failoverMaxTries") }


      if (!errors.isEmpty) { response.sendError(400, errors.mkString("; ")); return }

      var ids: util.List[String] = null
      try { ids = cluster.expandIds(idExpr) }
      catch { case e: IllegalArgumentException => response.sendError(400, "invalid id-expr"); return }

      val brokers = new util.ArrayList[Broker]()

      for (id <- ids) {
        var broker = cluster.getBroker(id)

        if (add)
          if (broker != null) errors.add(s"Broker $id already exists")
          else broker = new Broker(id)
        else
          if (broker == null) errors.add(s"Broker $id not found")
          else if (broker.active) errors.add(s"Broker $id is active")

        brokers.add(broker)
      }

      if (!errors.isEmpty) { response.sendError(400, errors.mkString("; ")); return }

      for (broker <- brokers) {
        if (host != null) broker.host = if (host != "") host else null
        if (cpus != null) broker.cpus = cpus
        if (mem != null) broker.mem = mem
        if (heap != null) broker.heap = heap

        if (options != null) broker.options = if (options != "") options else null
        if (attributes != null) broker.attributes = if (attributes != "") attributes else null

        if (failoverDelay != null) broker.failover.delay = failoverDelay
        if (failoverMaxDelay != null) broker.failover.maxDelay = failoverMaxDelay
        if (failoverMaxTries != null) broker.failover.maxTries = if (failoverMaxTries != "") Integer.valueOf(failoverMaxTries) else null

        if (add) cluster.addBroker(broker)
      }
      cluster.save()

      val brokerNodes = new ListBuffer[JSONObject]()
      for (broker <- brokers) brokerNodes.add(broker.toJson)

      response.getWriter.println("" + new JSONObject(Map("brokers" -> new JSONArray(brokerNodes.toList))))
    }

    def handleRemoveBroker(request: HttpServletRequest, response: HttpServletResponse): Unit = {
      val cluster = Scheduler.cluster

      val idExpr = request.getParameter("id")
      if (idExpr == null) { response.sendError(400, "id required"); return }

      var ids: util.List[String] = null
      try { ids = cluster.expandIds(idExpr) }
      catch { case e: IllegalArgumentException => response.sendError(400, "invalid id-expr"); return }

      val brokers = new util.ArrayList[Broker]()
      for (id <- ids) {
        val broker = Scheduler.cluster.getBroker(id)
        if (broker == null) { response.sendError(400, s"broker $id not found"); return }
        if (broker.active) { response.sendError(400, s"broker $id is active"); return }
        brokers.add(broker)
      }

      brokers.foreach(cluster.removeBroker)
      cluster.save()

      val result = new collection.mutable.LinkedHashMap[String, Any]()
      result("ids") = ids.mkString(",")

      response.getWriter.println(JSONObject(result.toMap))
    }

    def handleStartStopBroker(request: HttpServletRequest, response: HttpServletResponse): Unit = {
      val cluster: Cluster = Scheduler.cluster
      val start: Boolean = request.getRequestURI.endsWith("start")

      var timeout: Period = new Period("30s")
      if (request.getParameter("timeout") != null)
        try { timeout = new Period(request.getParameter("timeout")) }
        catch { case ignore: IllegalArgumentException => response.sendError(400, "invalid timeout"); return }

      val idExpr: String = request.getParameter("id")
      if (idExpr == null) { response.sendError(400, "id required"); return }

      var ids: util.List[String] = null
      try { ids = cluster.expandIds(idExpr) }
      catch { case e: IllegalArgumentException => response.sendError(400, "invalid id-expr"); return }

      val brokers = new util.ArrayList[Broker]()
      for (id <- ids) {
        val broker = cluster.getBroker(id)
        if (broker == null) { response.sendError(400, "broker " + id + " not found"); return }
        if (broker.active == start) { response.sendError(400, "broker " + id + " is" + (if (start) "" else " not") +  " active"); return }
        brokers.add(broker)
      }

      for (broker <- brokers) {
        broker.active = start
        broker.failover.resetFailures()
      }
      cluster.save()

      def waitForBrokers(): String = {
        if (timeout.ms == 0) return "scheduled"

        for (broker <- brokers)
          if (!broker.waitForState(start, timeout))
            return "timeout"

        if (start) "started" else "stopped"
      }
      val status = waitForBrokers()

      val result = new collection.mutable.LinkedHashMap[String, Any]()
      result("status") = status
      result("ids") = ids.mkString(",")

      response.getWriter.println(JSONObject(result.toMap))
    }

    def handleRebalance(request: HttpServletRequest, response: HttpServletResponse): Unit = {
      val cluster: Cluster = Scheduler.cluster

      val idExpr: String = request.getParameter("id")
      if (idExpr == null) { response.sendError(400, "id required"); return }

      var ids: util.List[String] = null
      try { ids = cluster.expandIds(idExpr) }
      catch { case e: IllegalArgumentException => response.sendError(400, "invalid id-expr"); return }

      val result = new collection.mutable.LinkedHashMap[String, Any]()
      result("success") = true
      result("ids") = ids.mkString(",")

      response.getWriter.println(JSONObject(result.toMap))
    }
  }
}
