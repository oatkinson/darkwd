name := """darkwd"""

version := "1.0"

scalaVersion := "2.10.1"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2-M3",
  "com.typesafe.akka" %% "akka-slf4j" % "2.2-M3",
  "com.typesafe.akka" %% "akka-testkit" % "2.2-M3",
  "net.databinder.dispatch" %% "dispatch-core" % "0.10.1",
  "net.debasishg" %% "sjson" % "0.19",
  "org.rogach" %% "scallop" % "0.9.3",
  "ch.qos.logback" % "logback-classic" % "1.0.0" % "runtime",
  "org.scalatest" % "scalatest_2.10" % "1.9.1" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.novocode" % "junit-interface" % "0.7" % "test->default"
)

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v")


// Note: These settings are defaults for activator, and reorganize your source directories.
Seq(
  scalaSource in Compile <<= baseDirectory / "app",
  javaSource in Compile <<= baseDirectory / "app",
  sourceDirectory in Compile <<= baseDirectory / "app",
  scalaSource in Test <<= baseDirectory / "test",
  javaSource in Test <<= baseDirectory / "test",
  sourceDirectory in Test <<= baseDirectory / "test",
  resourceDirectory in Compile <<= baseDirectory / "conf"
)
