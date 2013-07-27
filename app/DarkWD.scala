import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.event.Logging
import akka.kernel.Bootable
import akka.pattern.ask
import akka.routing.RoundRobinRouter
import akka.util.Timeout
import com.ning.http.client.Cookie
import com.splunk.JobResultsArgs.OutputMode
import com.splunk.Service._
import com.splunk.JobResultsArgs
import dispatch._, Defaults._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.Source
import scala.collection.JavaConversions._

/**
 * Author: AtkinsonOS
 */

case object DiffEvent
case class LogEvent(entry: String)

case class LogEntry( path: String, query: String, sessionId: String, duration: String)
object LogEntry {
  def toMap(s: String): Map[String, String] = {
    val headerIndex = s.indexOf("time")
    val host = s.take(headerIndex)
    val params = s.drop(headerIndex).split("\" ").toSeq map {
      pair: String => {
        val lst = pair.split("=", 2)
//        println("lst size=" + lst.size)
        if(lst.size == 2) {
//          println("lst=" +lst(0) + " -> " + lst(1))
          (lst(0), lst(1).stripMargin('"'))
        } else {
//          println("lst=" +lst(0) + " -> ")
          ( lst(0) , "")
        }
      }
    }
    val paramsMap:Map[String, String] = params.toMap
    Map("host" -> host) ++ paramsMap
  }

  def parse(s: String): LogEntry = {
    val p:Map[String, String] = LogEntry.toMap(s)
    LogEntry(p("path") ,p("query"), p("session_id"), p("duration"))
  }
}

object ReqLogListener{
  val opts: Map[String, Object] = Command("darkdw").opts
  val service = connect(opts)
}
class ReqLogListener(percentToGet: Int) extends Actor with ActorLogging {
  def receive: Actor.Receive = {
    case search:String => {
      log.debug("Executing search:  " + search)
      val job = ReqLogListener.service.getJobs.create(search)
      while (!job.isDone) {wait(500)}

      val resCount = job.getResultCount
      log.debug("Respons Count = " + resCount)
      val resArgs = new JobResultsArgs()
      resArgs.setOutputMode(OutputMode.RAW)
      val nReRequests: Int = resCount / 100 * percentToGet
      log.debug("\n\n\n\nrequesting " + percentToGet + "% of " + resCount + " or " + nReRequests)
      resArgs.setCount(nReRequests)
      val results = job.getResults(resArgs)
      val reader = Source.fromInputStream(results)
      val lines: Iterator[String] = reader.getLines()
      val resList = lines.toList
      resList.foreach(log.debug)
      sender ! resList
    }
  }
}

class Shadower extends Actor with ActorLogging {

  def isJson(le: LogEntry) = le.query.contains("dataFormat=") && le.query.contains("json")

  def q(le: LogEntry) = { le.query.concat(if (!isJson(le)) """&dataFormat=application/json""" else "")}
  val sessionId = context.system.settings.config.getString("darkwd.sessionId")

  def receive: Actor.Receive = {
    case le:LogEvent => {
      val logEntry = LogEntry.parse(le.entry)
      val sessionCookie = new Cookie("", "fssessionid", sessionId, "", 0, false)
      val targetHost = context.system.settings.config.getString("darkwd.targetHost")
      log.debug(s"darkwd.targetHost=$targetHost")
      val uri = targetHost + logEntry.path.replace("reservation/v1","oss") + "?" + q(logEntry)
      log.debug("Requesting Uri: " + uri)
      // make request to oss
      val ossSvc = url(uri).addCookie(sessionCookie)
      val ossPerson = Http(ossSvc OK as.String)
      val op = ossPerson() // wait for person response
      println(s"person=$op")
    }
  }
}

class Differ extends Actor with ActorLogging {

  def cleanOld(s: String) = {
    s.replaceAll(""""version":.*,""", """"version":null,""").replaceAll("""\"p\.""", "\"").replaceAll("""\"placeId\":\".*\"""","""""placeId":null""")
  }

  def cleanNew(s: String) = {
    s.replaceAll(""""version":".*"}$""", """""version":"1.3.20130703.0247"}""")
  }

  def diffString(first: String, second: String) = cleanOld(first) diff cleanNew(second)

  def isInteresting(s: String): Boolean = {s.size > 0}

  def o(s: String) = {}

  def logDiffStrings(req: String, a: String, b : String, diff: String) = {
    if(isInteresting(diff)) {
      val message = "apiRequest=" + req + "\ngenerated diff=" + diff + "\nbetween\napiPerson=" + a + "\nand\nossPerson=" + b
      println("\n\n\ndiff found: " + diff + "\n\n")
      log.info(message)
    }
  }

  def isJson(le: LogEntry) = le.query.contains("dataFormat=")

  def q(le: LogEntry) = { le.query.concat(if (!isJson(le)) """&dataFormat=application/json""" else "")}

  def receive: Actor.Receive = {
    case le:LogEvent => {
      val logEntry = LogEntry.parse(le.entry)
      val apUri: String = "https://api.familysearch.org" + logEntry.path + "?" + q(logEntry)
      o("Requesting v1 Uri: " + apUri)
      val sessionCookie = new Cookie("", "fssessionid", logEntry.sessionId, "", 0, false)
      val apiSvc = url(apUri).addCookie(sessionCookie)
      val apiPerson = Http(apiSvc OK as.String)

      val uri = "https://familysearch.org" + logEntry.path.replace("reservation/v1","oss") + "?" + q(logEntry)
      o("Requesting Uri: " + uri)
      // make request to oss
      val ossSvc = url(uri).addCookie(sessionCookie)
      val ossPerson = Http(ossSvc OK as.String)
      // make request to reservation/v1
      // get the time and result
      val diff = for {
        ap <- apiPerson
        op <- ossPerson
      } yield (ap, op, diffString(ap, op))
      // diff and log
      diff()
      //wait for the difference to be evaluated
      diff foreach (x => {logDiffStrings(apUri, x._1, x._2, x._3)})
    }
  }
}

case class Run()
case class Stopped()

class DWDMaster extends Actor with ActorLogging {
  val percentToLoad = context.system.settings.config.getInt("darkwd.percentLoad")
  o(s"darkwd.percentLoad=$percentToLoad")
  val nrOfWorkers = 60

  def o(s: String ) = {println(s)}

  val searchXml = """search index=production req_attr_developerKey="3Z3L-Z4GK-J7ZS-YT3Z-Q4KY-YN66-ZX5K-176R" method=GET /reservation/v1/* NOT agent="Dispatch/0.10.1" NOT path=*ordinances* NOT query=*json* NOT path=*status* source=*public-api-access.log earliest=-1m"""

  val searchJson = """search index=production req_attr_developerKey="3Z3L-Z4GK-J7ZS-YT3Z-Q4KY-YN66-ZX5K-176R" method=GET /reservation/v1/* NOT agent="Dispatch/0.10.1" NOT path=*ordinances* query=*json* NOT path=*status* source=*public-api-access.log earliest=-1m"""

  val diffWorkerRouter = {
    context.system.actorOf(Props[Differ].withRouter(RoundRobinRouter(nrOfWorkers)), name = "requestAndDiffWorkerRouter")
  }
  val logWorkerRouter = {
    context.system.actorOf(Props(new ReqLogListener(percentToLoad)).withRouter(RoundRobinRouter(3)), name = "SplunkListenerWorkerRouter")
  }

  // create an actor in a box
  val inbox = Inbox.create(context.system)
  implicit val timeout = Timeout(5 minutes)

  var stopping = false

  def process() = {
    var pass = 1
    while (!stopping) {
      o(s"Starting pass $pass")
      o(s"Stopping=$stopping")
      val resXmlList =logWorkerRouter ? searchXml
      val resJsonList =logWorkerRouter ? searchJson

      val reqXmlEntries: List[String] = Await.result(resXmlList, timeout.duration).asInstanceOf[List[String]]
      val reqJsonEntries: List[String] = Await.result(resJsonList, timeout.duration).asInstanceOf[List[String]]

      val nReRequests = reqXmlEntries.size
      o(s"requesting Xml $nReRequests")
      val nJsonReRequests = reqJsonEntries.size
      o(s"requesting Json $nJsonReRequests")
      val millisBetweenRequests = 60000 / (nReRequests + nJsonReRequests)
      var item = 1
      reqXmlEntries.take(nReRequests).foreach (r => {
        o(s"**** Sending XML Request $item/$nReRequests of pass $pass ****")
        diffWorkerRouter ! LogEvent(r)
        Thread.sleep(millisBetweenRequests)
        item = item + 1
      })
      item = 1
      reqJsonEntries.take(nJsonReRequests).foreach (r => {
        o(s"*** Sending JSON Request $item/$nJsonReRequests of pass $pass ***")
        diffWorkerRouter ! LogEvent(r)
        Thread.sleep(millisBetweenRequests)
        item = item + 1
      })
      o(s"***  Ending pass $pass ***")
      pass = pass + 1
    }
    o("Finished processing")
    DarkWD.inbox.getRef() ! Stopped
  }

  def receive: Actor.Receive = {
    case Run => {o("Recieved Start");Future{process()}}
    case Stop => {o("Recieved Stop");stopping = true; o(s"stopping=$stopping")}
  }
}
class DSMaster extends Actor with ActorLogging {
  val percentToLoad = context.system.settings.config.getInt("darkwd.percentLoad")
  o(s"darkwd.percentLoad=$percentToLoad")
  val nrOfWorkers = 60

  def o(s: String ) = {println(s)}

  val searchXml = """search index=production req_attr_developerKey="3Z3L-Z4GK-J7ZS-YT3Z-Q4KY-YN66-ZX5K-176R" method=GET /reservation/v1/* NOT agent="Dispatch/0.10.1" NOT path=*ordinances* NOT query=*json* NOT path=*status* source=*public-api-access.log earliest=-1m"""

  val searchJson = """search index=production req_attr_developerKey="3Z3L-Z4GK-J7ZS-YT3Z-Q4KY-YN66-ZX5K-176R" method=GET /reservation/v1/* NOT agent="Dispatch/0.10.1" NOT path=*ordinances* query=*json* NOT path=*status* source=*public-api-access.log earliest=-1m"""

  val shadowWorkerRouter = {
    context.system.actorOf(Props[Shadower].withRouter(RoundRobinRouter(nrOfWorkers)), name = "requestAndDiffWorkerRouter")
  }
  val logWorkerRouter = {
    context.system.actorOf(Props(new ReqLogListener(percentToLoad)).withRouter(RoundRobinRouter(3)), name = "SplunkListenerWorkerRouter")
  }

  // create an actor in a box
  val inbox = Inbox.create(context.system)
  implicit val timeout = Timeout(5 minutes)

  var stopping = false

  def process() = {
    var pass = 1
    while (!stopping) {
      o(s"Starting pass $pass")
      o(s"Stopping=$stopping")
      val resXmlList =logWorkerRouter ? searchXml
      val resJsonList =logWorkerRouter ? searchJson

      val reqXmlEntries: List[String] = Await.result(resXmlList, timeout.duration).asInstanceOf[List[String]]
      val reqJsonEntries: List[String] = Await.result(resJsonList, timeout.duration).asInstanceOf[List[String]]

      val nReRequests = reqXmlEntries.size
      o(s"requesting Xml $nReRequests")
      val nJsonReRequests = reqJsonEntries.size
      o(s"requesting Json $nJsonReRequests")
      val millisBetweenRequests = 60000 / (nReRequests + nJsonReRequests)
      var item = 1
      reqXmlEntries.take(nReRequests).foreach (r => {
        o(s"**** Sending XML Request $item/$nReRequests of pass $pass ****")
        shadowWorkerRouter ! LogEvent(r)
        Thread.sleep(millisBetweenRequests)
        item = item + 1
      })
      item = 1
      reqJsonEntries.take(nJsonReRequests).foreach (r => {
        o(s"*** Sending JSON Request $item/$nJsonReRequests of pass $pass ***")
        shadowWorkerRouter ! LogEvent(r)
        Thread.sleep(millisBetweenRequests)
        item = item + 1
      })
      o(s"***  Ending pass $pass ***")
      pass = pass + 1
    }
    o("Finished processing")
    DarkShadow.inbox.getRef() ! Stopped
  }

  def receive: Actor.Receive = {
    case Run => {o("Recieved Start");Future{process()}}
    case Stop => {o("Recieved Stop");stopping = true; o(s"stopping=$stopping")}
  }
}

object DarkWD {
  // Create the 'dakrkdw' actor system
  val system = ActorSystem("darkwd")
  val master =system.actorOf(Props[DWDMaster], name = "masterController")
  val inbox = Inbox.create(system)
//  new DarkWD().startup()
}

class DarkWD extends Bootable {
  import DarkWD._

  implicit val timeout = Timeout(2 minutes)

  def startup() = {
    master ! Run
  }

  def shutdown() = {
    master ! Stop
    println("Waiting for DWDMaster to finish current pass")
    val Stopped() = DarkWD.inbox.receive(3.minutes)
    println("Finished waiting")
    system.shutdown()
  }
}

object DarkShadow {
  // Create the 'dakrkshadow' actor system
  val system = ActorSystem("darkshadow")
  val master =system.actorOf(Props[DSMaster], name = "masterController")
  val inbox = Inbox.create(system)
  //  new DarkWD().startup()
}



class DarkShadow extends Bootable {
  import DarkShadow._

  implicit val timeout = Timeout(2 minutes)

  def startup() = {
    if (system.settings.config.getString("darkwd.sessionId") == "ChangeMe") {
      println("\n*********************\n\nERROR:   darkwd.sessionId must be changed from `ChangeMe' to a valid sessionId in the conf/application.conf file\n\n**********************\n")
      system.shutdown()
    } else {
      master ! Run
    }
  }

  def shutdown() = {
    master ! Stop
    println("Waiting for DSMaster to finish current pass")
    val Stopped() = DarkShadow.inbox.receive(3.minutes)
    println("Finished waiting")
    system.shutdown()
  }
}

