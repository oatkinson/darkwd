import com.ning.http.client.Cookie
import com.splunk.JobResultsArgs
import com.splunk.JobResultsArgs.OutputMode
import com.splunk.Service._
import dispatch._, Defaults._
import scala.collection.JavaConversions._


/**
 * Author: AtkinsonOS
 */
object HelloDispatch extends App {
  val search = args(0)
  val sessionId = args(1)
  val cookie = new Cookie("", "fssessionid", sessionId, "", 0, false)
  val ossSvc = url(search).addCookie(cookie)
  val ossPerson = Http(ossSvc)
  for( p <- ossPerson) {
    println(p.getResponseBody)
  }
  ossPerson()







}
