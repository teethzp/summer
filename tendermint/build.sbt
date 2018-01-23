name := "tendermint"

scalaVersion := "2.12.4"

lazy val akkaVersion = "2.5.6"

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/"

scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-Xfatal-warnings",
  "-Ywarn-dead-code",
  "-Ywarn-unused:implicits",
  "-Ywarn-unused:imports"
)

scalacOptions in console := Nil

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-remote" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "org.slf4j" % "slf4j-api" % "1.7.24",
  "org.slf4j" % "jcl-over-slf4j" % "1.7.24",
  "org.slf4j" % "log4j-over-slf4j" % "1.7.24",
  "ch.qos.logback" % "logback-classic" % "1.2.1",
  "org.scorexfoundation" %% "scrypto" % "2.0.0",
  "com.typesafe" % "config" % "1.3.1"
)
