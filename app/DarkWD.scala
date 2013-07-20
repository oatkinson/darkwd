import akka.actor.{Actor, Inbox, Props, ActorSystem}
import akka.event.{LogSource, Logging}
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
class ReqLogListener(percentToGet: Int) extends Actor  {
  val log = Logging(context.system, this)
  def receive: Actor.Receive = {
    case search:String => {
      log.debug("Executing search:  " + search)
      val job = ReqLogListener.service.getJobs.create(search)
      while (!job.isDone) {wait(500)}

      val resCount = job.getResultCount
      log.debug("Respons Count = " + resCount)
      var resArgs = new JobResultsArgs()
      resArgs.setOutputMode(OutputMode.RAW)
      val nReRequests: Int = resCount / 100 * percentToGet
      log.debug("\n\n\n\nrequesting " + percentToGet + "% of " + resCount + " or " + nReRequests)
      resArgs.setCount(nReRequests)
      val results = job.getResults(resArgs)
      val reader = Source.fromInputStream(results)
      val lines: Iterator[String] = reader.getLines()
      val resList = lines.toList
      resList.foreach(log.debug(_))
      sender ! resList
    }
  }
}

class DarkDiffer extends Actor {

  val log = Logging(context.system, this)

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

object DarkWD extends App {
  def o(s: String ) = {println(s)}
  val searchXml = """search index=production req_attr_developerKey="3Z3L-Z4GK-J7ZS-YT3Z-Q4KY-YN66-ZX5K-176R" method=GET /reservation/v1/* NOT agent="Dispatch/0.10.1" NOT path=*ordinances* NOT query=*json* NOT path=*status* source=*public-api-access.log earliest=-1m"""

  val searchJson = """search index=production req_attr_developerKey="3Z3L-Z4GK-J7ZS-YT3Z-Q4KY-YN66-ZX5K-176R" method=GET /reservation/v1/* NOT agent="Dispatch/0.10.1" NOT path=*ordinances* query=*json* NOT path=*status* source=*public-api-access.log earliest=-1m"""
  // Create the 'dakrkdw' actor system
  val system = ActorSystem("darkdw")

  // Create the 'logListener' and " actor
  val percentToLoad = 1
  val nrOfWorkers = 60
  val logWorkerRouter = {
    system.actorOf(Props(new ReqLogListener(percentToLoad)).withRouter(RoundRobinRouter(3)), name = "logWorkerRouter")
  }
  val xmlWorkerRouter = {
    system.actorOf(Props[DarkDiffer].withRouter(RoundRobinRouter(nrOfWorkers)), name = "xmlWorkerRouter")
  }
  val jsonWorkerRouter = {
    system.actorOf(Props[DarkDiffer].withRouter(RoundRobinRouter(nrOfWorkers)), name = "jsonWorkerRouter")
  }
  // create an actor in a box
  val inbox = Inbox.create(system)
  implicit val timeout = Timeout(5 minutes)
  for( pass <- 1 until 61) {

    val resXmlList =logWorkerRouter ? searchXml
    val resJsonList =logWorkerRouter ? searchJson

    val reqXmlEntries: List[String] = Await.result(resXmlList, timeout.duration).asInstanceOf[List[String]]
    val reqJsonEntries: List[String] = Await.result(resJsonList, timeout.duration).asInstanceOf[List[String]]

    val nReRequests = reqXmlEntries.size
    o("requesting Xml " + nReRequests)
    val nJsonReRequests = reqJsonEntries.size
    o("requesting Json " + nJsonReRequests)
    val millisBetweenRequests = 60000 / (nReRequests + nJsonReRequests)
    var item = 1
    reqXmlEntries.take(nReRequests).foreach (r => {
      o("**** Sending XML Request " + item + "/" + nReRequests + " pass " + pass + " ****")
      xmlWorkerRouter ! LogEvent(r)
      Thread.sleep(millisBetweenRequests)
      item = item + 1
    })
    item = 1
    reqJsonEntries.take(nJsonReRequests).foreach (r => {
      o("*** Sending JSON Request " + item + "/" + nJsonReRequests + " of pass " + pass + " ***")
      jsonWorkerRouter ! LogEvent(r)
      Thread.sleep(millisBetweenRequests)
      item = item + 1
    })
    o("***  Ending pass  " + pass + "***")
  }
  system.shutdown()
}
