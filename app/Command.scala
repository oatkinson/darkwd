import java.io.File
import scala.collection.immutable.HashMap
import scala.io.Source
import scala.util.Properties
import scala.collection.JavaConversions._


/**
 * Author: AtkinsonOS
 */

// Processes and capture command options and arguments

case class Command(appName: String = "App") {
  val defaultValues = HashMap[String, Object]("scheme" -> "https", "host" -> "localhost", "port" -> new Integer(8089))
  val rcLinesUnFiltered = Source.fromFile(Properties.userHome + File.separator + ".splunkrc").getLines().toList
  val splunkrc = rcLinesUnFiltered.filterNot( _.startsWith("#")).map(_.split("=")).map(e => (e(0), e(1))) toMap
  val opts: Map[String, Object] = defaultValues ++ splunkrc


}

object Command {
}
